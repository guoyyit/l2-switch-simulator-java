package com.github.guoyyit.l2switchsimulatorjava.sim.core;

import com.github.guoyyit.l2switchsimulatorjava.sim.model.DropEvent;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.DropReason;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.Frame;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.FramePriority;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.HostView;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.MacAddress;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.PortConfig;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.PortFault;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.SimulationView;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.SwitchConfig;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.SwitchView;
import com.github.guoyyit.l2switchsimulatorjava.sim.log.DecisionSource;
import com.github.guoyyit.l2switchsimulatorjava.sim.log.Event;
import com.github.guoyyit.l2switchsimulatorjava.sim.log.EventLog;
import com.github.guoyyit.l2switchsimulatorjava.sim.log.EventType;
import com.github.guoyyit.l2switchsimulatorjava.sim.log.LoggingDecisionSource;
import com.github.guoyyit.l2switchsimulatorjava.sim.log.RecordedDecisionSource;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 二层交换仿真服务入口。
 *
 * <p>所有配置变更、注入、tick 推进与状态查询都在同一把锁（本实例）内串行执行，
 * 因此：
 * <ul>
 *   <li>并发查询永远看到一致状态，不会观察到部分更新；</li>
 *   <li>固定种子 + 相同事件序列下，转发顺序、丢弃结果、统计逐次完全一致；</li>
 *   <li>所有随机源都由端口专属的确定性 {@link java.util.Random} 提供，
 *       某端口的故障注入不会消耗其他端口的随机序列。</li>
 * </ul>
 */
public final class Simulation {

    private final long globalSeed;
    private long tick;
    private final Map<String, SwitchDevice> switches = new TreeMap<>();
    private final Map<String, Host> hosts = new TreeMap<>();
    private final Topology topology = new Topology();
    private final List<DropEvent> dropEvents = new ArrayList<>();
    private final Map<DropReason, Long> dropReasonCounts = new EnumMap<>(DropReason.class);
    private long injected;
    private long hostDelivered;
    private final EventLog eventLog = new EventLog();
    private DecisionSource decisionSource = new LoggingDecisionSource(eventLog);

    public Simulation(long seed) {
        this.globalSeed = seed;
        for (DropReason r : DropReason.values()) {
            dropReasonCounts.put(r, 0L);
        }
    }

    /** 包级可见：供回放引擎替换为“按日志取判定”的判定源。 */
    void setDecisionSource(DecisionSource decisionSource) {
        this.decisionSource = java.util.Objects.requireNonNull(decisionSource, "decisionSource");
    }

    /** 包级可见：回放引擎读取原始事件日志。 */
    EventLog eventLog() {
        return eventLog;
    }

    public synchronized String addSwitch(String name, SwitchConfig config) {
        requireNotExists(name);
        decisionSource.registerSwitch(name, config.seed());
        switches.put(name, new SwitchDevice(name, config, decisionSource));
        eventLog.append(tick, EventType.SWITCH_ADDED,
                Map.of("name", name, "agingTicks", config.agingTicks(), "seed", config.seed()));
        return name;
    }

    public synchronized int addPort(String switchName, boolean hostFacing, PortConfig config) {
        int id = sw(switchName).addPort(config, hostFacing);
        eventLog.append(tick, EventType.PORT_ADDED, Map.of("switchName", switchName, "portId", id,
                "hostFacing", hostFacing,
                "capacityPerPriority", config.capacityPerPriority(),
                "starvationTicks", config.starvationTicks(),
                "backpressure", config.backpressure().name(),
                "stormThreshold", config.stormThreshold(),
                "stormWindow", config.stormWindow(),
                "stormAction", config.stormAction().name()));
        return id;
    }

    public synchronized void setPortEnabled(String switchName, int portId, boolean enabled) {
        sw(switchName).setPortEnabled(portId, enabled);
        eventLog.append(tick, EventType.PORT_ENABLED,
                Map.of("switchName", switchName, "portId", portId, "enabled", enabled));
    }

    public synchronized void configurePort(String switchName, int portId, PortConfig config) {
        sw(switchName).configurePort(portId, config);
        eventLog.append(tick, EventType.PORT_CONFIGURED, Map.of("switchName", switchName, "portId", portId,
                "capacityPerPriority", config.capacityPerPriority(),
                "starvationTicks", config.starvationTicks(),
                "backpressure", config.backpressure().name(),
                "stormThreshold", config.stormThreshold(),
                "stormWindow", config.stormWindow(),
                "stormAction", config.stormAction().name()));
    }

    public synchronized void setFault(String switchName, int portId, PortFault fault) {
        sw(switchName).setFault(portId, fault);
        eventLog.append(tick, EventType.FAULT_SET, Map.of("switchName", switchName, "portId", portId,
                "dropRate", fault.dropRate(), "reorderEnabled", fault.reorderEnabled()));
    }

    public synchronized void setVlan(String switchName, int portId, int vlan) {
        sw(switchName).setVlan(portId, vlan);
        eventLog.append(tick, EventType.VLAN_SET,
                Map.of("switchName", switchName, "portId", portId, "vlan", vlan));
    }

    public synchronized String addHost(String name, String mac) {
        requireHostNotExists(name);
        hosts.put(name, new Host(name, MacAddress.of(mac)));
        eventLog.append(tick, EventType.HOST_ADDED, Map.of("name", name, "mac", mac));
        return name;
    }

    public synchronized void connectHost(String hostName, String switchName, int portId) {
        Host h = host(hostName);
        SwitchDevice s = sw(switchName);
        if (s.portCount() <= portId) {
            throw new IllegalArgumentException("端口不存在: " + switchName + ":" + portId);
        }
        // 从旧端口摘离
        detachFromCurrent(h);
        h.attach(switchName, portId);
        s.attachHost(portId, hostName, h.mac());
        eventLog.append(tick, EventType.HOST_CONNECTED,
                Map.of("hostName", hostName, "switchName", switchName, "portId", portId));
    }

    public synchronized void disconnectHost(String hostName) {
        Host h = host(hostName);
        detachFromCurrent(h);
        h.detach();
        eventLog.append(tick, EventType.HOST_DISCONNECTED, Map.of("hostName", hostName));
    }

    private void detachFromCurrent(Host h) {
        HostView cur = h.snapshot();
        if (cur.switchName() != null) {
            switches.get(cur.switchName()).detachHost(cur.portId());
        }
    }

    public synchronized void addLink(String switchA, int portA, String switchB, int portB) {
        sw(switchA);
        sw(switchB);
        topology.addLink(switchA, portA, switchB, portB);
        reconverge();
        eventLog.append(tick, EventType.LINK_ADDED, Map.of("switchA", switchA, "portA", portA,
                "switchB", switchB, "portB", portB));
    }

    public synchronized void removeLink(String switchA, int portA, String switchB, int portB) {
        topology.removeLink(switchA, portA, switchB, portB);
        reconverge();
        eventLog.append(tick, EventType.LINK_REMOVED, Map.of("switchA", switchA, "portA", portA,
                "switchB", switchB, "portB", portB));
    }

    /** 重新计算生成树并把阻断结果下发到各交换机（主机端口永不阻断）。 */
    private void reconverge() {
        List<String> blocked = topology.recomputeBlocking((switchName, portId) -> {
            SwitchView v = switches.get(switchName).snapshot(tick);
            return v.ports().get(portId).hostFacing();
        });
        for (SwitchDevice s : switches.values()) {
            for (int p = 0; p < s.portCount(); p++) {
                String key = s.name() + ":" + p;
                s.setBlocked(p, blocked.contains(key));
            }
        }
    }

    /** 主机注入一帧：构造并尝试进入接入端口的入队列。 */
    public synchronized void inject(String hostName, String dstMac, int vlan,
                                    FramePriority priority, String payload) {
        Host h = host(hostName);
        HostView loc = h.snapshot();
        if (loc.switchName() == null) {
            throw new IllegalStateException("主机未接入交换机: " + hostName);
        }
        long frameId = injected;
        injected++;
        Frame frame = new Frame(frameId, h.mac(), MacAddress.of(dstMac), vlan, priority, payload);
        SwitchDevice s = switches.get(loc.switchName());
        eventLog.append(tick, EventType.FRAME_INJECTED, Map.of("frameId", frameId,
                "hostName", hostName, "src", h.mac().toString(), "dst", dstMac, "vlan", vlan,
                "priority", priority.value(), "payload", payload == null ? "" : payload));
        // 入端拒绝（禁用/阻断/队列满）在此记录事件；帧未进入队列，
        // 主机注入场景没有后续 processIngress，因此端口计数也在此补一次
        DropReason reason = s.admitIngress(loc.portId(), frame, true);
        if (reason != null) {
            recordDrop(reason, frame, loc.switchName(), loc.portId());
        }
    }

    /** 推进一个 tick：老化 → 入处理 → 出处理。 */
    public synchronized void tick() {
        tick++;
        eventLog.append(tick, EventType.TICK, Map.of("tick", tick));
        for (SwitchDevice s : switches.values()) {
            s.ageFdb(tick);
        }
        for (SwitchDevice s : switches.values()) {
            final String switchName = s.name();
            SwitchDevice.EventSink perSwitch = new SwitchDevice.EventSink() {
                @Override
                public void dropped(int portId, DropReason reason, Frame frame) {
                    recordDrop(reason, frame, switchName, portId);
                }

                @Override
                public void flooded(int portId, Frame frame) {
                }

                @Override
                public void learned(int portId, Frame frame) {
                }
            };
            s.processIngress(tick, perSwitch);
        }
        for (SwitchDevice s : switches.values()) {
            final String switchName = s.name();
            SwitchDevice.Transmitter transmitter = (egressPortId, frame) ->
                    transmit(switchName, egressPortId, frame);
            s.processEgress(tick, transmitter);
        }
    }

    /** 把出端口帧投递到下挂主机或邻居交换机的入端口。 */
    private DropReason transmit(String switchName, int portId, Frame frame) {
        SwitchDevice s = switches.get(switchName);
        SwitchView view = s.snapshot(tick);
        var pv = view.ports().get(portId);
        if (pv.hostFacing() && pv.attachedHost() != null) {
            Host h = hosts.get(pv.attachedHost());
            if (h != null) {
                h.receive(frame);
                hostDelivered++;
                eventLog.append(tick, EventType.FRAME_DELIVERED, Map.of("frameId", frame.id(),
                        "hostName", h.name(), "switchName", switchName, "portId", portId));
            }
            return null;
        }
        var neighbor = topology.neighbor(switchName, portId);
        if (neighbor.isEmpty()) {
            return null;
        }
        Topology.Endpoint ep = neighbor.get();
        SwitchDevice ns = switches.get(ep.switchName());
        // 帧进入邻居入队列；若被拒绝则当场计数（该帧不会再进入 processIngress），
        // 事件与端口计数都只在此发生一次
        DropReason reason = ns.admitIngress(ep.portId(), frame, true);
        if (reason != null) {
            recordDrop(reason, frame, ep.switchName(), ep.portId());
        }
        return null;
    }

    private void recordDrop(DropReason reason, Frame frame, String switchName, int portId) {
        dropEvents.add(new DropEvent(tick, switchName, portId, reason, frame));
        dropReasonCounts.merge(reason, 1L, Long::sum);
        eventLog.append(tick, EventType.DROP, Map.of("frameId", frame.id(), "switchName", switchName,
                "portId", portId, "reason", reason.name()));
    }

    public synchronized void tick(int n) {
        for (int i = 0; i < n; i++) {
            tick();
        }
    }

    public synchronized long now() {
        return tick;
    }

    public synchronized List<DropEvent> dropEvents() {
        return List.copyOf(dropEvents);
    }

    public synchronized long dropCount(DropReason reason) {
        return dropReasonCounts.getOrDefault(reason, 0L);
    }

    public synchronized List<Frame> hostReceived(String hostName) {
        return host(hostName).received();
    }

    public synchronized SimulationView snapshot() {
        List<SwitchView> switchViews = new ArrayList<>(switches.size());
        long rx = 0;
        long tx = 0;
        long learned = 0;
        long flooded = 0;
        long dropped = 0;
        long floodNet = 0;
        long filtered = 0;
        for (SwitchDevice s : switches.values()) {
            SwitchView v = s.snapshot(tick);
            switchViews.add(v);
            rx += v.totals().getOrDefault("rxFrames", 0L);
            tx += v.totals().getOrDefault("txFrames", 0L);
            learned += v.totals().getOrDefault("learned", 0L);
            flooded += v.totals().getOrDefault("flooded", 0L);
            dropped += v.totals().getOrDefault("dropped", 0L);
            floodNet += s.floodNetCopies();
            filtered += s.filtered();
        }
        List<HostView> hostViews = new ArrayList<>(hosts.size());
        for (Host h : hosts.values()) {
            hostViews.add(h.snapshot());
        }
        List<String> blockedPorts = new ArrayList<>();
        for (SwitchView v : switchViews) {
            for (var p : v.ports()) {
                if (p.blocked()) {
                    blockedPorts.add(v.name() + ":" + p.portId());
                }
            }
        }
        blockedPorts.sort(String::compareTo);
        Map<String, Long> reasons = new LinkedHashMap<>();
        for (DropReason r : DropReason.values()) {
            reasons.put(r.name(), dropReasonCounts.getOrDefault(r, 0L));
        }
        Map<String, Long> totals = new LinkedHashMap<>();
        totals.put("injected", injected);
        totals.put("hostDelivered", hostDelivered);
        totals.put("rxFrames", rx);
        totals.put("txFrames", tx);
        totals.put("learned", learned);
        totals.put("flooded", flooded);
        totals.put("dropped", dropped);
        totals.put("dropEvents", (long) dropEvents.size());
        totals.put("floodNetCopies", floodNet);
        totals.put("filtered", filtered);
        return new SimulationView(tick, List.copyOf(switchViews), List.copyOf(hostViews),
                topology.linksView(), List.copyOf(blockedPorts),
                Map.copyOf(reasons), Map.copyOf(totals));
    }

    public synchronized void reset() {
        this.tick = 0;
        this.switches.clear();
        this.hosts.clear();
        this.topology.clear();
        this.dropEvents.clear();
        this.dropReasonCounts.clear();
        for (DropReason r : DropReason.values()) {
            this.dropReasonCounts.put(r, 0L);
        }
        this.injected = 0;
        this.hostDelivered = 0;
        this.eventLog.clear();
    }

    /** 回放结果：回放结束时刻的状态、[fromSeq,toSeq] 内的事件与日志窗口元数据。 */
    public record ReplayResult(SimulationView state, List<Event> events, List<DropEvent> dropEvents,
                               long fromSeq, long toSeq, long oldestSeq, long latestSeq,
                               long replayedEvents) {
    }

    /** 查询事件日志中 [fromSeq, toSeq]（含端点）的事件，按 seq 升序。 */
    public List<Event> events(long fromSeq, long toSeq) {
        validateRange(fromSeq, toSeq);
        return eventLog.eventsBetween(fromSeq, toSeq);
    }

    /** 事件日志状态（开关、容量、当前条数、已记录窗口）。 */
    public Map<String, Object> eventLogStatus() {
        return Map.of("enabled", eventLog.isEnabled(), "capacity", eventLog.capacity(),
                "size", eventLog.size(), "oldestSeq", eventLog.oldestSeq(), "latestSeq", eventLog.latestSeq());
    }

    /** 配置事件日志开关与容量（容量缩小时按 FIFO 淘汰最旧事件）。 */
    public void configureEventLog(boolean enabled, int capacity) {
        eventLog.configure(enabled, capacity);
    }

    /**
     * 按记录的事件序列在隔离的仿真上下文中回放 [fromSeq, toSeq]。
     *
     * <p>回放不修改在线仿真状态、不消耗真实端口队列；回放使用独立的 {@link Simulation}，
     * 转发判定由日志中记录的值按序给出，因此多次回放与原始运行逐次一致。
     */
    public ReplayResult replay(long fromSeq, long toSeq) {
        long oldest = eventLog.oldestSeq();
        long latest = eventLog.latestSeq();
        if (latest < 0) {
            throw new IllegalStateException("事件日志为空，无法回放");
        }
        validateRange(fromSeq, toSeq, oldest, latest);
        Simulation replaySim = new Simulation(globalSeed);
        replaySim.configureEventLog(false, eventLog.capacity());
        replaySim.setDecisionSource(new RecordedDecisionSource(eventLog));
        int replayed = ReplayEngine.fold(replaySim, eventLog, toSeq);
        List<Event> events = eventLog.eventsBetween(fromSeq, toSeq);
        return new ReplayResult(replaySim.snapshot(), events, replaySim.dropEvents(), fromSeq, toSeq,
                oldest, latest, replayed);
    }

    private static void validateRange(long fromSeq, long toSeq) {
        if (fromSeq < 0) {
            throw new IllegalArgumentException("回放范围非法: fromSeq=" + fromSeq + " < 0");
        }
        if (toSeq < fromSeq) {
            throw new IllegalArgumentException("回放范围非法: toSeq=" + toSeq + " < fromSeq=" + fromSeq);
        }
    }

    private static void validateRange(long fromSeq, long toSeq, long oldest, long latest) {
        validateRange(fromSeq, toSeq);
        if (fromSeq < oldest) {
            throw new IllegalArgumentException(
                    "回放范围已被日志淘汰: fromSeq=" + fromSeq + " < oldestSeq=" + oldest);
        }
        if (oldest > 0) {
            throw new IllegalStateException(
                    "事件日志已被淘汰: oldestSeq=" + oldest + "，无法完整回放");
        }
        if (toSeq > latest) {
            throw new IllegalArgumentException(
                    "回放范围超出已记录范围: toSeq=" + toSeq + " > latestSeq=" + latest);
        }
    }

    private SwitchDevice sw(String name) {
        SwitchDevice s = switches.get(name);
        if (s == null) {
            throw new IllegalArgumentException("交换机不存在: " + name);
        }
        return s;
    }

    private Host host(String name) {
        Host h = hosts.get(name);
        if (h == null) {
            throw new IllegalArgumentException("主机不存在: " + name);
        }
        return h;
    }

    private void requireNotExists(String name) {
        if (switches.containsKey(name)) {
            throw new IllegalArgumentException("交换机已存在: " + name);
        }
    }

    private void requireHostNotExists(String name) {
        if (hosts.containsKey(name)) {
            throw new IllegalArgumentException("主机已存在: " + name);
        }
    }
}
