package com.github.guoyyit.l2switchsimulatorjava.core;

import com.github.guoyyit.l2switchsimulatorjava.fdb.FdbEntry;
import com.github.guoyyit.l2switchsimulatorjava.fdb.FdbInvalidationReason;
import com.github.guoyyit.l2switchsimulatorjava.fdb.ForwardingDatabase;
import com.github.guoyyit.l2switchsimulatorjava.model.Frame;
import com.github.guoyyit.l2switchsimulatorjava.model.MacAddress;
import com.github.guoyyit.l2switchsimulatorjava.port.BackpressureMode;
import com.github.guoyyit.l2switchsimulatorjava.port.Port;
import com.github.guoyyit.l2switchsimulatorjava.port.PortConfig;
import com.github.guoyyit.l2switchsimulatorjava.port.PortStatus;
import com.github.guoyyit.l2switchsimulatorjava.storm.StormController;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 一台仿真二层交换机：端口集合 + 转发表 + 每端口风暴控制器 + 转发处理。
 *
 * 设计约定：
 * - 非线程安全，由 Network 在统一锁内串行驱动（并发快照也在同一把锁内完成）；
 * - 帧从主机口入向进入时带端口 VLAN 标记；fabric 口收到的帧已带标记，且要求与入端口 VLAN 相同；
 * - 泛洪范围：同 VLAN、转发中、排除入端口；广播/组播/未知单播均受风暴控制约束；
 * - 背压 DROP：输出队列满则丢帧；PAUSE：暂停入端口固定 tick 数，当前帧丢弃；
 * - fabric 口出队的帧通过 fabricDelivery 回调交给 Network 投递到对端。
 */
public final class Switch {

    /** PAUSE 背压触发后，入端口暂停的 tick 数。 */
    public static final long PAUSE_TICKS = 4L;

    private static final int MAX_DROP_LOG = 512;

    private final String name;
    private final SwitchConfig config;
    private final List<Port> ports;
    private final ForwardingDatabase fdb;
    private final DeterministicRandom rng;
    private final List<StormController> stormControllers;
    private final ArrayDeque<DropEventView> dropLog = new ArrayDeque<>();
    private Consumer<FabricDelivery> fabricDelivery;

    public Switch(String name, int portCount, SwitchConfig config, DeterministicRandom rng) {
        if (portCount <= 0) {
            throw new IllegalArgumentException("portCount must be positive");
        }
        this.name = name;
        this.config = config;
        this.rng = rng;
        this.ports = new ArrayList<>(portCount);
        this.stormControllers = new ArrayList<>(portCount);
        for (int i = 0; i < portCount; i++) {
            PortConfig pc = new PortConfig(0, config.defaultQueueCapacity, config.backpressureMode, false);
            Port port = new Port(i, pc);
            // 每个端口派生独立的确定随机子流，避免故障注入相互干扰。
            port.initRandom(new DeterministicRandom(rng.nextLong() ^ (((long) i + 1L) * 0x9E3779B97F4A7C15L)));
            ports.add(port);
            stormControllers.add(new StormController(config.stormConfig));
        }
        this.fdb = new ForwardingDatabase(config.agingTicks);
    }

    public String getName() { return name; }
    public SwitchConfig getConfig() { return config; }
    public List<Port> getPorts() { return ports; }
    public ForwardingDatabase getFdb() { return fdb; }
    public DeterministicRandom getRng() { return rng; }
    public StormController stormController(int index) { return stormControllers.get(index); }

    public Port port(int index) {
        if (index < 0 || index >= ports.size()) {
            throw new IndexOutOfBoundsException("no port " + index + " on " + name);
        }
        return ports.get(index);
    }

    public void setFabricDelivery(Consumer<FabricDelivery> fabricDelivery) {
        this.fabricDelivery = fabricDelivery;
    }

    /** 从某主机端口注入帧（入方向），立即完成本交换芯片上的转发处理。 */
    public void receiveFromHost(int ingress, Frame frame, long nowTick) {
        int accepted = entranceCheck(ingress, frame, nowTick, true);
        if (accepted < 0) {
            return;
        }
        frame.setVlan(port(accepted).getConfig().getVlan());
        process(accepted, frame, nowTick);
    }

    /** 从互联（fabric）口收到对端交换机转发来的帧（帧已带 VLAN 标记）。 */
    public void receiveFromFabric(int ingress, Frame frame, long nowTick) {
        int accepted = entranceCheck(ingress, frame, nowTick, false);
        if (accepted < 0) {
            return;
        }
        Port port = port(accepted);
        if (!port.getConfig().isFabricPort()) {
            logDrop(nowTick, accepted, frame, DropReason.VLAN_MISMATCH);
            return;
        }
        if (port.getConfig().getVlan() != frame.vlan()) {
            logDrop(nowTick, accepted, frame, DropReason.VLAN_MISMATCH);
            return;
        }
        process(accepted, frame, nowTick);
    }

    /**
     * 入方向公共检查。通过返回端口索引；帧被丢弃返回 -1。
     * fromHost=true 表示源自主机（入向故障注入）；fabric 来帧的故障在对端出方向已处理。
     */
    private int entranceCheck(int ingress, Frame frame, long nowTick, boolean fromHost) {
        Port port;
        try {
            port = port(ingress);
        } catch (IndexOutOfBoundsException e) {
            logDrop(nowTick, ingress, frame, DropReason.PORT_DISABLED);
            return -1;
        }
        if (!port.isAdminUp()) {
            logDrop(nowTick, ingress, frame, DropReason.PORT_DISABLED);
            return -1;
        }
        if (port.isBlocked()) {
            logDrop(nowTick, ingress, frame, DropReason.PORT_BLOCKED);
            return -1;
        }
        if (port.isPaused(nowTick)) {
            port.getStatistics().pausedIngressEvents++;
            logDrop(nowTick, ingress, frame, DropReason.PORT_PAUSED);
            return -1;
        }
        if (fromHost && port.injectFaultOnIngress(frame)) {
            logDrop(nowTick, ingress, frame, DropReason.FAULT_INJECTION);
            return -1;
        }
        port.getStatistics().receivedFrames++;
        return ingress;
    }

    /** 学习源地址并按目的地址转发。 */
    private void process(int ingress, Frame frame, long nowTick) {
        if (frame.incrementHops() > config.maxFrameHops) {
            logDrop(nowTick, ingress, frame, DropReason.TTL_EXPIRED);
            return;
        }
        if (frame.src().isUnicast()) {
            fdb.learn(frame.src(), ingress, frame.vlan(), nowTick);
        }
        if (frame.isBroadcast() || frame.isMulticast()) {
            flood(ingress, frame, nowTick, true);
        } else {
            int egress = fdb.lookup(frame.dst(), frame.vlan(), nowTick);
            if (egress >= 0) {
                forwardSingle(ingress, egress, frame, nowTick);
            } else {
                flood(ingress, frame, nowTick, false);
            }
        }
    }

    /** 向单个已知端口转发。 */
    private void forwardSingle(int ingress, int egress, Frame frame, long nowTick) {
        if (egress == ingress) {
            // 目的与源在同端口：不回送，视为无可交付端口但不计数丢弃（静默过滤）。
            return;
        }
        Port out = port(egress);
        if (!eligible(out, frame.vlan())) {
            logDrop(nowTick, egress, frame, DropReason.NO_OUTPUT_PORTS);
            return;
        }
        deliverToEgress(egress, frame, nowTick, false);
    }

    /**
     * 泛洪：同 VLAN、管理启用、未阻断、排除入端口。
     * @param broadcast true 表示广播/组播（记 flooded 与 forwarded）；false 为未知单播泛洪。
     */
    private void flood(int ingress, Frame frame, long nowTick, boolean broadcast) {
        boolean deliveredAny = false;
        for (int p = 0; p < ports.size(); p++) {
            if (p == ingress) {
                continue;
            }
            Port out = ports.get(p);
            if (!eligible(out, frame.vlan())) {
                continue;
            }
            // 风暴控制：广播、组播、未知单播泛洪均受控。
            if (stormControllers.get(p).shouldDrop(nowTick)) {
                out.getStatistics().stormDroppedFrames++;
                out.getStatistics().droppedFrames++;
                logDrop(nowTick, p, frame, DropReason.STORM_CONTROL);
                continue;
            }
            if (deliverToEgress(p, frame, nowTick, true)) {
                deliveredAny = true;
            }
        }
        if (deliveredAny) {
            Port in = port(ingress);
            in.getStatistics().floodedFrames++;
            if (broadcast) {
                in.getStatistics().forwardedFrames++;
            }
        }
    }

    private boolean eligible(Port port, int vlan) {
        return port.isForwarding() && port.getConfig().getVlan() == vlan;
    }

    /**
     * 将帧放入出端口队列。返回 true 表示入队成功。
     * flooded=true 时调用方已做风暴计数；入端口 floodedFrames 仍按交付结果统计。
     */
    private boolean deliverToEgress(int egress, Frame frame, long nowTick, boolean flooded) {
        Port out = port(egress);
        if (out.enqueueOutput(frame, nowTick)) {
            out.getStatistics().forwardedFrames++;
            return true;
        }
        // 队列满：背压。
        out.getStatistics().queueFullDroppedFrames++;
        out.getStatistics().droppedFrames++;
        if (out.getConfig().getBackpressureMode() == BackpressureMode.PAUSE) {
            // 暂停入端口（帧的来源端口），抑制其后续注入。
            int ingress = findIngressOf(frame, egress, nowTick);
            if (ingress >= 0) {
                port(ingress).pause(nowTick + PAUSE_TICKS);
                port(ingress).getStatistics().pausedIngressEvents++;
            }
        }
        logDrop(nowTick, egress, frame, DropReason.QUEUE_FULL);
        return false;
    }

    /**
     * PAUSE 背压下定位入端口：优先从转发表查源 MAC；查不到时暂停所有同 VLAN 的 host 口无意义，
     * 因此采用确定策略——返回帧源 MAC 学到的端口；未知则返回 -1（仅丢帧）。
     */
    private int findIngressOf(Frame frame, int failedEgress, long nowTick) {
        if (frame.src().isUnicast()) {
            return fdb.lookup(frame.src(), frame.vlan(), nowTick);
        }
        return -1;
    }

    /** 将某主机口输出队列中的帧交付（移出队列）；返回成功交付的帧数。出方向故障在此生效。 */
    public int drainHostPort(int portIndex, long nowTick) {
        Port port = port(portIndex);
        int delivered = 0;
        while (!port.getOutputQueue().isEmpty()) {
            Frame frame = port.dequeueOutput(nowTick);
            if (frame != null) {
                delivered++;
            }
            // frame == null 表示队首帧被出方向故障丢弃，继续处理后续帧。
        }
        return delivered;
    }

    /** 将某 fabric 口输出队列中的帧逐帧投递到对端交换机。 */
    public int drainFabricPort(int portIndex, long nowTick) {
        Port port = port(portIndex);
        int delivered = 0;
        while (!port.getOutputQueue().isEmpty()) {
            Frame frame = port.dequeueOutput(nowTick);
            if (frame == null) {
                continue; // 出方向故障丢弃
            }
            if (fabricDelivery != null) {
                fabricDelivery.accept(new FabricDelivery(name, portIndex, frame));
            }
            delivered++;
        }
        return delivered;
    }

    public void enablePort(int index) {
        port(index).enable();
    }

    /** 禁用端口：端口上学到的转发表条目按确定策略立即失效。 */
    public void disablePort(int index) {
        Port port = port(index);
        if (port.isAdminUp()) {
            port.disable();
            fdb.invalidatePort(index, FdbInvalidationReason.PORT_DISABLED);
            port.getOutputQueue().clear();
        }
    }

    /** STP 阻断集合（本交换机端口索引）；阻断集合变化触发拓扑变化，转发表全量失效。 */
    public void applyBlockedPorts(Set<Integer> blocked) {
        boolean changed = false;
        for (int i = 0; i < ports.size(); i++) {
            boolean shouldBlock = blocked != null && blocked.contains(i);
            Port port = ports.get(i);
            if (port.isBlocked() != shouldBlock) {
                port.setBlocked(shouldBlock);
                changed = true;
                if (shouldBlock) {
                    port.getOutputQueue().clear();
                }
            }
        }
        if (changed) {
            fdb.flushAll(FdbInvalidationReason.TOPOLOGY_CHANGE);
        }
    }

    /** 修改端口转发域：原 VLAN 上该端口相关条目失效（按 VLAN 失效策略）。 */
    public void changePortVlan(int index, int vlan) {
        Port port = port(index);
        int oldVlan = port.getConfig().getVlan();
        if (oldVlan == vlan) {
            return;
        }
        fdb.invalidatePort(index, FdbInvalidationReason.VLAN_CHANGED);
        port.getConfig().setVlan(vlan);
        port.getOutputQueue().clear();
    }

    /** 将某端口标记为 fabric 互联口（建链时调用）。 */
    public void markFabricPort(int index, int vlan) {
        Port port = port(index);
        port.getConfig().setFabricPort(true);
        port.getConfig().setVlan(vlan);
    }

    /** 老化清理并推进每端口风暴控制器的限速时钟。 */
    public void age(long nowTick) {
        fdb.sweep(nowTick);
        for (StormController controller : stormControllers) {
            controller.tick(nowTick);
        }
    }

    private void logDrop(long nowTick, int portIndex, Frame frame, DropReason reason) {
        dropLog.addLast(new DropEventView(nowTick, name, portIndex, frame.id(), reason.name()));
        while (dropLog.size() > MAX_DROP_LOG) {
            dropLog.removeFirst();
        }
    }

    public List<DropEventView> recentDrops() {
        return new ArrayList<>(dropLog);
    }

    public void clearDropLog() {
        dropLog.clear();
    }

    /** 一致快照：调用方须持有网络级锁，保证统计/队列/FDB 不会被部分更新读到。 */
    public SwitchView snapshot(long nowTick) {
        List<PortView> portViews = new ArrayList<>(ports.size());
        for (Port port : ports) {
            ArrayDeque<Frame> qCopy = new ArrayDeque<>(port.getOutputQueue().snapshotByPriority());
            portViews.add(new PortView(
                    port.getIndex(),
                    port.getStatus().name(),
                    port.getConfig().getVlan(),
                    port.getOutputQueue().getCapacity(),
                    port.getOutputQueue().size(),
                    port.getConfig().isFabricPort(),
                    port.isPaused(nowTick),
                    port.getPausedUntilTick(),
                    port.getStatistics().snapshot(),
                    qCopy));
        }
        List<FdbEntryView> fdbViews = new ArrayList<>();
        for (FdbEntry entry : fdb.entries()) {
            fdbViews.add(new FdbEntryView(entry.getMac().toString(), entry.getPortIndex(),
                    entry.getVlan(), entry.getLearnedAtTick(), entry.getLastRefreshedTick()));
        }
        return new SwitchView(name, nowTick, ports.size(),
                Collections.unmodifiableList(portViews), Collections.unmodifiableList(fdbViews));
    }

    public record FabricDelivery(String fromSwitch, int fromPort, Frame frame) { }
}
