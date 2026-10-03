package com.github.guoyyit.l2switchsimulatorjava.sim.core;

import com.github.guoyyit.l2switchsimulatorjava.sim.model.BackpressureAction;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.DropReason;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.Frame;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.MacAddress;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.PortConfig;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.PortCountersView;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.PortFault;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.PortView;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.SwitchConfig;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.SwitchView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 单台仿真交换机。
 *
 * <p>每个 tick 分两个阶段（均在仿真全局锁内串行执行）：
 * <ol>
 *   <li>{@link #processIngress}：按端口号升序处理各入端口暂存队列，
 *       完成故障判定、源地址学习、已知单播转发或广播/组播/未知单播泛洪、
 *       风暴控制与入端背压；</li>
 *   <li>{@link #processEgress}：各输出端口用防饿死的优先级调度出队一帧，
 *       交予 {@code transmitter}（主机收帧或投递到邻居入端口）。</li>
 * </ol>
 * 帧的排队时刻以 tick 记录，供输出调度做等待老化。
 */
public final class SwitchDevice {

    /**
     * 转发过程中的事件回调，由仿真层统一记录。
     * 所有回调都在仿真全局锁内、按真实发生顺序触发。
     */
    public interface EventSink {
        /** 一帧真实到达并入端口暂存队列（每帧每端口至多一次）。 */
        void admitted(int portId, Frame frame);

        /** 一帧到达后在入队前被拒绝（禁用/阻断/入队列满）。 */
        void rejected(int portId, DropReason reason, Frame frame);

        /** 一次故障随机判定（同一帧只发生一次，重试复用结果）。 */
        void faultVerdict(int portId, Frame frame, boolean dropDrawn, boolean reorderDrawn);

        /** 源 MAC 新增学习。 */
        void learned(int portId, Frame frame);

        /** 一份泛洪副本从该端口发出。 */
        void flooded(int portId, Frame frame);

        /** 帧被留在队首等待后续 tick 重试（风暴 LIMIT 或背压 PAUSE）。 */
        void deferred(int portId, Frame frame, String cause);

        void dropped(int portId, DropReason reason, Frame frame);
    }

    /**
     * 故障随机判定来源。在线运行时用端口专属 {@link Random} 抽取并经事件汇录；
     * 回放时由记录的判定结果提供，保证同一帧命运不变。
     */
    public interface FaultChance {
        /**
         * @return 两元素判定：[0]=是否丢帧，[1]=是否错序交换
         */
        boolean[] decide(int portId, Frame frame, double dropRate, boolean reorderEnabled);
    }

    /** 默认在线判定：每端口独立 {@link Random}。 */
    public final class RandomFaultChance implements FaultChance {
        @Override
        public boolean[] decide(int portId, Frame frame, double dropRate, boolean reorderEnabled) {
            Random rng = port(portId).faultRng;
            boolean drop = rng.nextDouble() < dropRate;
            // 仅在启用错序时才消耗这枚硬币，保持与既有序列相同的随机消费次序
            boolean reorder = reorderEnabled && rng.nextDouble() < 0.5;
            return new boolean[]{drop, reorder};
        }
    }

    /** 出帧投递结果：null 表示成功，否则为邻居入端口给出的丢弃原因。 */
    public interface Transmitter {
        DropReason transmit(int egressPortId, Frame frame);
    }

    /** 帧真正从出端口发出时计一次发送（由仿真层 transmit 调用）。 */
    public void countTx(int portId) {
        port(portId).txFrames++;
    }

    private static final class Port {
        final int id;
        final boolean hostFacing;
        int vlan;
        boolean enabled = true;
        boolean blocked;
        String attachedHost;
        MacAddress hostMac;
        PortConfig config;
        PortFault fault = PortFault.none();
        final PortQueues queues;
        StormGuard storm;
        final Random faultRng;
        long rxFrames;
        long txFrames;
        long learned;
        long flooded;
        long dropped;
        long pausedTicks;
        long ingressArrivals;
        long admissionRejected;
        long filtered;
        long ingressDropped;
        long egressDropped;

        Port(int id, boolean hostFacing, int vlan, PortConfig config, Random faultRng) {
            this.id = id;
            this.hostFacing = hostFacing;
            this.vlan = vlan;
            this.config = config;
            this.faultRng = faultRng;
            this.queues = new PortQueues(config.capacityPerPriority());
            this.storm = new StormGuard(config.stormThreshold(), config.stormWindow(), config.stormAction());
        }
    }

    private enum IngressResult {
        DONE, RETRY_HEAD, PAUSED
    }

    private final String name;
    private final long seed;
    private long agingTicks;
    private final List<Port> ports = new ArrayList<>();
    private ForwardingTable fdb;
    private FaultChance faultChance = new RandomFaultChance();

    public SwitchDevice(String name, SwitchConfig config) {
        this.name = name;
        this.seed = config.seed();
        this.agingTicks = config.agingTicks();
        this.fdb = new ForwardingTable(agingTicks);
    }

    /** 注入故障判定来源（回放时使用记录结果）。 */
    public void setFaultChance(FaultChance chance) {
        this.faultChance = chance == null ? new RandomFaultChance() : chance;
    }

    public String name() {
        return name;
    }

    public int portCount() {
        return ports.size();
    }

    private Port port(int portId) {
        if (portId < 0 || portId >= ports.size()) {
            throw new IllegalArgumentException("交换机 " + name + " 不存在端口 " + portId);
        }
        return ports.get(portId);
    }

    /**
     * 统一丢弃计数归类（保证统计可交叉核对）：
     * <ul>
     *   <li>入端拒绝（帧未入队）：admissionRejected；</li>
     *   <li>入处理丢弃（已到达、在入处理阶段判定）：ingressDropped；</li>
     *   <li>输出侧丢弃（已进入转发决策后）：egressDropped。</li>
     * </ul>
     * 三者都计入 {@code dropped} 总量，但口径互不重叠。
     */
    private static void countDrop(Port p, DropReason reason) {
        p.dropped++;
        switch (reason) {
            case DISABLED_INGRESS, LOOP_BLOCKED_INGRESS, INGRESS_QUEUE_FULL ->
                    p.admissionRejected++;
            case FAULT_DROP, STORM_CONTROL, NO_MEMBER_PORTS ->
                    p.ingressDropped++;
            case QUEUE_FULL, DISABLED_EGRESS, LOOP_BLOCKED_EGRESS ->
                    p.egressDropped++;
        }
    }

    public int addPort(PortConfig config, boolean hostFacing) {
        int id = ports.size();
        long portSeed = seed ^ (0x9E3779B97F4A7C15L * (id + 1L));
        ports.add(new Port(id, hostFacing, 1, config, new Random(portSeed)));
        return id;
    }

    public void setPortEnabled(int portId, boolean enabled, EventSink sink) {
        Port p = port(portId);
        if (p.enabled == enabled) {
            return;
        }
        p.enabled = enabled;
        if (!enabled) {
            // 确定性失效策略：端口禁用立即清空队列并删除该端口学到的条目。
            // 清出的帧是真实到达/排队过的帧：逐一记录丢弃，保证统计守恒、
            // 不出现“已到达/已发送却凭空消失”的帧。
            clearQueuesAsDropped(p, sink);
            fdb.invalidatePort(portId);
        }
    }

    /** 清空端口全部队列并把帧计为丢弃：入端口暂存帧计 INGRESS 类，输出队列帧计 EGRESS 类。 */
    private void clearQueuesAsDropped(Port p, EventSink sink) {
        DropReason ingressReason = DropReason.DISABLED_INGRESS;
        PortQueues.IngressFrame item;
        while ((item = p.queues.pollIngress()) != null) {
            countDrop(p, ingressReason);
            if (sink != null) {
                sink.dropped(p.id, ingressReason, item.frame());
            }
        }
        Frame f;
        while ((f = p.queues.pollAnyEgress()) != null) {
            countDrop(p, DropReason.DISABLED_EGRESS);
            if (sink != null) {
                sink.dropped(p.id, DropReason.DISABLED_EGRESS, f);
            }
        }
        p.queues.setIngressPaused(false);
    }

    public boolean isPortEnabled(int portId) {
        return port(portId).enabled;
    }

    public void configurePort(int portId, PortConfig config) {
        Port p = port(portId);
        if (config.capacityPerPriority() > p.config.capacityPerPriority()) {
            p.queues.resizeCapacity(config.capacityPerPriority());
        }
        p.config = config;
        p.storm = new StormGuard(config.stormThreshold(), config.stormWindow(), config.stormAction());
    }

    public void setFault(int portId, PortFault fault) {
        port(portId).fault = fault == null ? PortFault.none() : fault;
    }

    public void setVlan(int portId, int vlan) {
        Port p = port(portId);
        if (p.vlan != vlan) {
            // 转发域变化：该端口学习条目按确定策略失效
            fdb.invalidatePort(p.id);
            p.vlan = vlan;
        }
    }

    public void attachHost(int portId, String hostName, MacAddress hostMac) {
        Port p = port(portId);
        p.attachedHost = hostName;
        p.hostMac = hostMac;
    }

    public void detachHost(int portId) {
        Port p = port(portId);
        p.attachedHost = null;
        p.hostMac = null;
        fdb.invalidatePort(portId);
    }

    public void setBlocked(int portId, boolean blocked) {
        port(portId).blocked = blocked;
    }

    public boolean isBlocked(int portId) {
        return port(portId).blocked;
    }

    /**
     * 主机或邻居向端口入队一帧。
     *
     * <p>是否计数 dropped 由 {@code countDrop} 决定：仿真层对所有入端拒绝统一只计一次数，
     * 避免跨交换机投递时重复计数。
     *
     * @return 丢弃原因；null 表示已进入入端口暂存队列
     */
    /**
     * 主机或邻居向端口入队一帧。
     *
     * <p>到达即计数：成功进入入端口暂存队列计一次 {@code ingressArrivals}
     * （后续暂停/限速重试不再计数，保证同一帧只计一次接收）；
     * 入队前被拒绝计一次 {@code admissionRejected} 并返回拒绝原因。
     *
     * @return 拒绝原因；null 表示已进入入端口暂存队列
     */
    public DropReason admitIngress(int portId, Frame frame, EventSink sink) {
        Port p = port(portId);
        DropReason reason = null;
        if (!p.enabled) {
            reason = DropReason.DISABLED_INGRESS;
        } else if (p.blocked) {
            reason = DropReason.LOOP_BLOCKED_INGRESS;
        } else if (!p.queues.offerIngress(frame)) {
            reason = DropReason.INGRESS_QUEUE_FULL;
        }
        if (reason != null) {
            countDrop(p, reason);
            if (sink != null) {
                sink.rejected(portId, reason, frame);
            }
        } else {
            p.ingressArrivals++;
            if (sink != null) {
                sink.admitted(portId, frame);
            }
        }
        return reason;
    }

    /** tick 阶段 1：逐端口、逐帧处理入端口队列。 */
    public void processIngress(long now, EventSink sink) {
        for (Port p : ports) {
            if (!p.enabled) {
                drainDropped(p, sink);
                continue;
            }
            if (p.blocked) {
                drainDropped(p, sink);
                continue;
            }
            // 上一 tick 的暂停在本 tick 先重试队首；仍背压则继续暂停并累计 1 tick
            p.queues.setIngressPaused(false);
            processOnePortIngress(p, now, sink);
            if (p.queues.isIngressPaused()) {
                p.pausedTicks++;
            }
        }
    }

    private void drainDropped(Port p, EventSink sink) {
        DropReason reason = p.enabled ? DropReason.LOOP_BLOCKED_INGRESS : DropReason.DISABLED_INGRESS;
        PortQueues.IngressFrame item;
        while ((item = p.queues.pollIngress()) != null) {
            countDrop(p, reason);
            sink.dropped(p.id, reason, item.frame());
        }
        // 阻断不清输出队列（输出仍可调度，符合既有语义）；暂停态随队列排空而解除
        p.queues.setIngressPaused(false);
    }

    private void processOnePortIngress(Port p, long now, EventSink sink) {
        while (true) {
            PortQueues.IngressFrame headItem = p.queues.peekIngress();
            if (headItem == null) {
                return;
            }

            // 故障判定对同一帧只做一次：首次处理时抽取（并记录），
            // 之后无论 PAUSE 还是风暴 LIMIT 导致多少次重试，都复用冻结结果。
            if (!headItem.decided()) {
                boolean[] verdict = faultChance.decide(p.id, headItem.frame(),
                        p.fault.dropRate(), p.fault.reorderEnabled());
                headItem.decide(verdict[0], verdict[1]);
                sink.faultVerdict(p.id, headItem.frame(), verdict[0], verdict[1]);
            }

            if (headItem.dropDrawn()) {
                p.queues.pollIngress();
                countDrop(p, DropReason.FAULT_DROP);
                sink.dropped(p.id, DropReason.FAULT_DROP, headItem.frame());
                continue;
            }

            // 故障错序：同一帧至多参与一次交换。队首若有后继、自身未参与过交换，
            // 且首次判定的错序硬币命中，则把后继取出立即处理，原队首留下一轮处理。
            PortQueues.IngressFrame item = headItem;
            boolean headWillStay = false;
            if (!headItem.swapExempt() && p.fault.reorderEnabled()
                    && p.queues.ingressDepth() >= 2 && headItem.reorderDrawn()) {
                item = p.queues.swapAndPollFirstTwo();
                // 原队首（仍在队首）已参与一次交换，此后不再参与
                p.queues.peekIngress().markSwapExempt();
                // 被取出的后继立即处理；它本身也已参与过一次交换
                item.markSwapExempt();
                headWillStay = true;
            }
            Frame frame = item.frame();

            if (fdb.learn(frame.src(), p.id, frame.vlan(), now)) {
                p.learned++;
                sink.learned(p.id, frame);
            }

            long ingressDropsBefore = p.ingressDropped;
            long filteredBefore = p.filtered;
            IngressResult result;
            if (frame.isGroup()) {
                result = handleGroup(p, frame, now, sink);
            } else {
                result = handleUnicast(p, frame, now, sink);
            }
            if (result == IngressResult.RETRY_HEAD) {
                // 交换命中时 item（后继）已出队、原队首仍在队列；
                // 未交换时 item 仍在队首，需先取出。两种情况统一放回队首，
                // 得到 [item, 原队首, ...]，下一 tick 优先重试 item。
                if (!headWillStay) {
                    p.queues.pollIngress();
                }
                requeueAtHead(p, item);
                sink.deferred(p.id, frame, "STORM_LIMIT");
                return;
            }
            if (result == IngressResult.PAUSED) {
                if (!headWillStay) {
                    p.queues.pollIngress();
                }
                requeueAtHead(p, item);
                p.queues.setIngressPaused(true);
                sink.deferred(p.id, frame, "PAUSE");
                return;
            }
            // DONE：若交换后原队首仍在队列里，本帧（后继）已取出，正常移除即可；
            // 非交换情形需把队首取出。
            if (!headWillStay) {
                p.queues.pollIngress();
            }
            // 只有真正完成入处理（未在入处理阶段被丢弃）的帧才计接收
            if (p.ingressDropped == ingressDropsBefore && p.filtered == filteredBefore) {
                p.rxFrames++;
            }
        }
    }

    private void requeueAtHead(Port p, PortQueues.IngressFrame item) {
        List<PortQueues.IngressFrame> rest = new ArrayList<>();
        PortQueues.IngressFrame f;
        while ((f = p.queues.pollIngress()) != null) {
            rest.add(f);
        }
        p.queues.offerIngress(item);
        for (PortQueues.IngressFrame r : rest) {
            p.queues.offerIngress(r);
        }
    }

    /** 广播/组播帧：先过风暴控制，再在转发域内泛洪。 */
    private IngressResult handleGroup(Port p, Frame frame, long now, EventSink sink) {
        StormGuard.Decision decision = p.storm.observe(now);
        if (decision == StormGuard.Decision.DROP) {
            countDrop(p, DropReason.STORM_CONTROL);
            sink.dropped(p.id, DropReason.STORM_CONTROL, frame);
            return IngressResult.DONE;
        }
        if (decision == StormGuard.Decision.LIMIT) {
            // 限速：帧保留在队首，下一个 tick 优先重试。
            // 风暴判定是与 tick 相关的时间决策，重试时重新评估；
            // 但故障判定已冻结，绝不重抽。deferred 由主循环统一记录。
            return IngressResult.RETRY_HEAD;
        }
        return flood(p, frame, now, sink);
    }

    /** 单播：查 (MAC,VLAN)；已知走指定端口，未知则泛洪。 */
    private IngressResult handleUnicast(Port p, Frame frame, long now, EventSink sink) {
        var target = fdb.lookup(frame.dst(), frame.vlan());
        if (target.isEmpty()) {
            return flood(p, frame, now, sink);
        }
        Port out = ports.get(target.get().portId());
        if (out.id == p.id) {
            // 目的与源在同一端口（如发往本端口下挂主机），交换机静默过滤。
            // 计入 filtered 以保持“到达 == 丢弃 + 发出排队 + 过滤”守恒。
            p.filtered++;
            return IngressResult.DONE;
        }
        if (out.vlan != frame.vlan()) {
            return flood(p, frame, now, sink);
        }
        if (!out.enabled) {
            countDrop(p, DropReason.DISABLED_EGRESS);
            sink.dropped(p.id, DropReason.DISABLED_EGRESS, frame);
            return IngressResult.DONE;
        }
        if (out.blocked) {
            countDrop(p, DropReason.LOOP_BLOCKED_EGRESS);
            sink.dropped(p.id, DropReason.LOOP_BLOCKED_EGRESS, frame);
            return IngressResult.DONE;
        }
        if (!out.queues.offer(frame, now)) {
            return handleFullEgress(p, out, frame, sink);
        }
        return IngressResult.DONE;
    }

    /**
     * 泛洪到转发域内除入端口外的所有转发端口。
     * PAUSE 策略采用整帧原子语义：只要任一候选队列满且要求暂停，则本帧不出任何副本，
     * 入端口暂停，避免暂停恢复后产生重复副本。
     */
    private IngressResult flood(Port in, Frame frame, long now, EventSink sink) {
        List<Port> candidates = new ArrayList<>();
        for (Port out : ports) {
            if (out.id == in.id || !out.enabled || out.blocked || out.vlan != frame.vlan()) {
                continue;
            }
            candidates.add(out);
        }
        if (candidates.isEmpty()) {
            countDrop(in, DropReason.NO_MEMBER_PORTS);
            sink.dropped(in.id, DropReason.NO_MEMBER_PORTS, frame);
            return IngressResult.DONE;
        }
        for (Port out : candidates) {
            if (!out.queues.hasCapacity(frame.priority())
                    && out.config.backpressure() == BackpressureAction.PAUSE) {
                return IngressResult.PAUSED;
            }
        }
        int copies = 0;
        for (Port out : candidates) {
            if (out.queues.offer(frame, now)) {
                copies++;
            } else {
                // DROP 策略下该副本丢弃，其余副本照常
                countDrop(in, DropReason.QUEUE_FULL);
                sink.dropped(in.id, DropReason.QUEUE_FULL, frame);
            }
        }
        in.flooded += copies;
        for (int i = 0; i < copies; i++) {
            sink.flooded(in.id, frame);
        }
        return IngressResult.DONE;
    }

    private IngressResult handleFullEgress(Port in, Port out, Frame frame, EventSink sink) {
        if (out.config.backpressure() == BackpressureAction.PAUSE) {
            return IngressResult.PAUSED;
        }
        countDrop(in, DropReason.QUEUE_FULL);
        sink.dropped(in.id, DropReason.QUEUE_FULL, frame);
        return IngressResult.DONE;
    }

    /**
     * tick 阶段 2：每端口防饿死调度出队一帧并投递。
     * 帧一旦离开本端口即计入本端口 tx；投递到邻居入端口后的丢弃由仿真层在邻居侧记录一次。
     */
    /**
     * tick 阶段 2：每端口防饿死调度出队一帧并投递。
     *
     * <p>帧一旦离开本端口即计入本端口 tx；transmitter 返回的丢弃原因表示
     * 接收端（邻居入端口）拒绝该帧，丢弃归因于接收端口，由仿真层在邻居侧计数，
     * 不在发送端重复计数。
     */
    public void processEgress(long now, Transmitter transmitter) {
        for (Port p : ports) {
            if (!p.enabled) {
                continue;
            }
            Frame frame = p.queues.poll(now, p.config.starvationTicks());
            if (frame == null) {
                continue;
            }
            transmitter.transmit(p.id, frame);
            // 发送计数由仿真层 transmit 统一在真正发出时计一次
            // （processEgress 自身不再加，避免与 transmit 双重计数）。
        }
    }

    public void ageFdb(long now) {
        fdb.age(now);
    }

    public int fdbSize() {
        return fdb.size();
    }

    public SwitchView snapshot(long tick) {
        List<PortView> portViews = new ArrayList<>(ports.size());
        long rx = 0;
        long tx = 0;
        long learned = 0;
        long flooded = 0;
        long dropped = 0;
        long paused = 0;
        long arrivals = 0;
        long rejected = 0;
        long filtered = 0;
        long ingressDropped = 0;
        long egressDropped = 0;
        for (Port p : ports) {
            PortCountersView c = new PortCountersView(p.rxFrames, p.txFrames, p.learned, p.flooded,
                    p.dropped, p.pausedTicks, p.ingressArrivals, p.admissionRejected, p.filtered,
                    p.ingressDropped, p.egressDropped);
            portViews.add(new PortView(p.id, p.enabled, p.blocked, p.hostFacing,
                    p.queues.isIngressPaused(), p.vlan, p.attachedHost, p.fault,
                    p.queues.ingressDepth(), p.queues.depths(), c));
            rx += p.rxFrames;
            tx += p.txFrames;
            learned += p.learned;
            flooded += p.flooded;
            dropped += p.dropped;
            paused += p.pausedTicks;
            arrivals += p.ingressArrivals;
            rejected += p.admissionRejected;
            filtered += p.filtered;
            ingressDropped += p.ingressDropped;
            egressDropped += p.egressDropped;
        }
        Map<String, Long> totals = new LinkedHashMap<>();
        totals.put("rxFrames", rx);
        totals.put("txFrames", tx);
        totals.put("learned", learned);
        totals.put("flooded", flooded);
        totals.put("dropped", dropped);
        totals.put("pausedTicks", paused);
        totals.put("ingressArrivals", arrivals);
        totals.put("admissionRejected", rejected);
        totals.put("filtered", filtered);
        totals.put("ingressDropped", ingressDropped);
        totals.put("egressDropped", egressDropped);
        return new SwitchView(name, tick, agingTicks, true, portViews, fdb.snapshot(),
                Map.copyOf(totals));
    }

}
