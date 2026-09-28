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

    /** 转发/丢弃事件回调，由仿真层统一记录。 */
    public interface EventSink {
        void dropped(int portId, DropReason reason, Frame frame);

        void flooded(int portId, Frame frame);

        void learned(int portId, Frame frame);
    }

    /** 出帧投递结果：null 表示成功，否则为邻居入端口给出的丢弃原因。 */
    public interface Transmitter {
        DropReason transmit(int egressPortId, Frame frame);
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

    public SwitchDevice(String name, SwitchConfig config) {
        this.name = name;
        this.seed = config.seed();
        this.agingTicks = config.agingTicks();
        this.fdb = new ForwardingTable(agingTicks);
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

    public int addPort(PortConfig config, boolean hostFacing) {
        int id = ports.size();
        long portSeed = seed ^ (0x9E3779B97F4A7C15L * (id + 1L));
        ports.add(new Port(id, hostFacing, 1, config, new Random(portSeed)));
        return id;
    }

    public void setPortEnabled(int portId, boolean enabled) {
        Port p = port(portId);
        if (p.enabled == enabled) {
            return;
        }
        p.enabled = enabled;
        if (!enabled) {
            // 确定性失效策略：端口禁用立即清空队列并删除该端口学到的条目
            p.queues.clear();
            fdb.invalidatePort(portId);
        }
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
    public DropReason admitIngress(int portId, Frame frame, boolean countDrop) {
        Port p = port(portId);
        DropReason reason = null;
        if (!p.enabled) {
            reason = DropReason.DISABLED_INGRESS;
        } else if (p.blocked) {
            reason = DropReason.LOOP_BLOCKED_INGRESS;
        } else if (!p.queues.offerIngress(frame)) {
            reason = DropReason.QUEUE_FULL;
        }
        if (reason != null && countDrop) {
            p.dropped++;
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
        Frame f;
        while ((f = p.queues.pollIngress()) != null) {
            p.dropped++;
            sink.dropped(p.id, reason, f);
        }
    }

    private void processOnePortIngress(Port p, long now, EventSink sink) {
        boolean skipSwapForHead = false;
        while (true) {
            Frame frame = p.queues.peekIngress();
            if (frame == null) {
                return;
            }
            // 故障丢帧：端口独立的确定性随机源，不影响其他端口的随机序列
            if (p.fault.dropRate() > 0.0 && p.faultRng.nextDouble() < p.fault.dropRate()) {
                p.queues.pollIngress();
                p.dropped++;
                sink.dropped(p.id, DropReason.FAULT_DROP, frame);
                skipSwapForHead = false;
                continue;
            }
            // 故障错序：队首若有后继且未参与过上一次交换，则投一次硬币；
            // 命中则与后继交换并立即处理后继，被换到队首的原队首跳过下一次抛硬币，
            // 因此每帧至多参与一次交换，结果确定且不会来回抖动
            if (!skipSwapForHead && p.fault.reorderEnabled() && p.queues.ingressDepth() >= 2
                    && p.faultRng.nextDouble() < 0.5) {
                frame = p.queues.swapAndPollFirstTwo();
                skipSwapForHead = true;
            } else {
                p.queues.pollIngress();
                skipSwapForHead = false;
            }
            p.rxFrames++;

            if (fdb.learn(frame.src(), p.id, frame.vlan(), now)) {
                p.learned++;
                sink.learned(p.id, frame);
            }

            IngressResult result;
            if (frame.isGroup()) {
                result = handleGroup(p, frame, now, sink);
            } else {
                result = handleUnicast(p, frame, now, sink);
            }
            if (result == IngressResult.RETRY_HEAD) {
                requeueAtHead(p, frame);
                return;
            }
            if (result == IngressResult.PAUSED) {
                requeueAtHead(p, frame);
                p.queues.setIngressPaused(true);
                return;
            }
        }
    }

    private void requeueAtHead(Port p, Frame frame) {
        List<Frame> rest = new ArrayList<>();
        Frame f;
        while ((f = p.queues.pollIngress()) != null) {
            rest.add(f);
        }
        p.queues.offerIngress(frame);
        for (Frame r : rest) {
            p.queues.offerIngress(r);
        }
    }

    /** 广播/组播帧：先过风暴控制，再在转发域内泛洪。 */
    private IngressResult handleGroup(Port p, Frame frame, long now, EventSink sink) {
        StormGuard.Decision decision = p.storm.observe(now);
        if (decision == StormGuard.Decision.DROP) {
            p.dropped++;
            sink.dropped(p.id, DropReason.STORM_CONTROL, frame);
            return IngressResult.DONE;
        }
        if (decision == StormGuard.Decision.LIMIT) {
            // 限速：帧保留在队首，下一个 tick 优先重试
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
            // 目的与源在同一端口（如发往本端口下挂主机），交换机静默过滤
            return IngressResult.DONE;
        }
        if (out.vlan != frame.vlan()) {
            return flood(p, frame, now, sink);
        }
        if (!out.enabled) {
            p.dropped++;
            sink.dropped(p.id, DropReason.DISABLED_EGRESS, frame);
            return IngressResult.DONE;
        }
        if (out.blocked) {
            p.dropped++;
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
            in.dropped++;
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
                in.dropped++;
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
        in.dropped++;
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
            p.txFrames++;
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
        for (Port p : ports) {
            PortCountersView c = new PortCountersView(p.rxFrames, p.txFrames, p.learned, p.flooded,
                    p.dropped, p.pausedTicks);
            portViews.add(new PortView(p.id, p.enabled, p.blocked, p.hostFacing,
                    p.queues.isIngressPaused(), p.vlan, p.attachedHost, p.fault, p.queues.depths(), c));
            rx += p.rxFrames;
            tx += p.txFrames;
            learned += p.learned;
            flooded += p.flooded;
            dropped += p.dropped;
            paused += p.pausedTicks;
        }
        Map<String, Long> totals = new LinkedHashMap<>();
        totals.put("rxFrames", rx);
        totals.put("txFrames", tx);
        totals.put("learned", learned);
        totals.put("flooded", flooded);
        totals.put("dropped", dropped);
        totals.put("pausedTicks", paused);
        return new SwitchView(name, tick, agingTicks, true, portViews, fdb.snapshot(),
                Map.copyOf(totals));
    }

}

