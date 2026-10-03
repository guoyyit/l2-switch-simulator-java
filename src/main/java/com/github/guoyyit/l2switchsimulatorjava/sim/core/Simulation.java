package com.github.guoyyit.l2switchsimulatorjava.sim.core;

import com.github.guoyyit.l2switchsimulatorjava.sim.model.DropEvent;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.DropReason;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.EventLogOptions;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.EventView;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.Frame;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.FramePriority;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.HostView;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.MacAddress;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.PortConfig;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.PortFault;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.ReplayResult;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.ReplayRejectedException;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.SimEvent;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.SimulationView;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.SwitchConfig;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.SwitchView;

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
 *
 * <p><b>事件记录与真实回放</b>：运行期间持续记录影响转发结果的完整事件序列
 * （外部命令 + 内部观测），任何一次运行都可事后在隔离上下文中按记录逐事件重建，
 * 结束时刻的转发表、端口状态、统计与丢弃明细与原运行完全一致。
 */
public final class Simulation {

    private final long globalSeed;
    private long tick;
    private final Map<String, SwitchDevice> switches = new TreeMap<>();
    private final Map<String, Host> hosts = new TreeMap<>();
    private final Topology topology = new Topology();
    private final EventLog eventLog;
    private final List<DropEvent> dropEvents = new ArrayList<>();
    private final Map<DropReason, Long> dropReasonCounts = new EnumMap<>(DropReason.class);
    private long injected;
    private long hostDelivered;

    public Simulation(long seed) {
        this(seed, EventLogOptions.defaults());
    }

    public Simulation(long seed, EventLogOptions eventLogOptions) {
        this.globalSeed = seed;
        this.eventLog = new EventLog(eventLogOptions);
        this.recorder = f -> eventLog.record(this::now, f);
        for (DropReason r : DropReason.values()) {
            dropReasonCounts.put(r, 0L);
        }
        recordEvent((seq, t) -> new SimEvent.Begin(seq, t, seed));
    }

    // ------------------------------------------------------------------
    // 事件记录
    // ------------------------------------------------------------------

    /** 事件记录器：在线模式写入日志；回放模式逐事件比对并复用原始序号。 */
    interface Recorder {
        long record(EventLog.EventFactory factory);
    }

    private Recorder recorder;
    private SwitchDevice.FaultChance faultChanceOverride;

    /** 包内可见：回放引擎安装校验型记录器与故障判定源。 */
    synchronized void installReplayHooks(Recorder r, SwitchDevice.FaultChance chance) {
        this.recorder = r;
        this.faultChanceOverride = chance;
        for (SwitchDevice s : switches.values()) {
            s.setFaultChance(chance);
        }
    }

    private long recordEvent(EventLog.EventFactory factory) {
        return recorder.record(factory);
    }

    public synchronized boolean eventRecordingEnabled() {
        return eventLog.enabled();
    }

    public synchronized long eventCount() {
        return eventLog.size();
    }

    public synchronized long lastEventSeq() {
        return eventLog.lastSeq();
    }

    public synchronized long droppedEvents() {
        return eventLog.droppedNew();
    }

    public synchronized boolean eventLogTruncated() {
        return eventLog.truncated();
    }

    public synchronized List<EventView> events(Long fromSeq, Long toSeq) {
        long from = fromSeq == null ? 1L : fromSeq;
        long to = toSeq == null ? eventLog.lastSeq() : toSeq;
        if (from < 1 || to < from) {
            throw new IllegalArgumentException("非法事件范围: from=" + from + ", to=" + to);
        }
        if (to > eventLog.lastSeq()) {
            throw new IllegalArgumentException("事件终点超出已记录范围: to=" + to
                    + ", last=" + eventLog.lastSeq());
        }
        List<EventView> out = new ArrayList<>();
        for (SimEvent e : eventLog.snapshot()) {
            if (e.seq() >= from && e.seq() <= to) {
                out.add(EventView.of(e));
            }
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------
    // 真实回放：在隔离上下文中按记录事件序列重建
    // ------------------------------------------------------------------

    /** 全量回放：从最近一次起点重建到已记录末尾。 */
    public ReplayResult replay() {
        return replayRange(null, null);
    }

    /**
     * 按事件序号范围回放。
     *
     * <p>语义：从 {@code fromSeq} 所在命令边界（含）之前的最近一次 {@link SimEvent.Begin}
     * 开始重建（确保状态依赖完整），依次执行命令并逐事件比对观测事件，直到 {@code toSeq}
     * （含）。{@code toSeq} 必须是命令事件或 {@link SimEvent.TickEnd}，即一个确定边界。
     * 返回该边界时刻的完整快照，以及 {@code fromSeq..toSeq} 内的事件视图。
     *
     * <p>整个过程在新建的隔离 {@code Simulation} 中进行，不读取/修改在线仿真状态，
     * 不触碰其队列；在线仿真可继续推进与查询。
     */
    public ReplayResult replayRange(Long fromSeq, Long toSeq) {
        // 在在线仿真锁内只做：读取日志快照（不可变拷贝），随后立即释放锁。
        List<SimEvent> log;
        long maxSeq;
        synchronized (this) {
            if (!eventLog.enabled()) {
                throw new ReplayRejectedException(ReplayRejectedException.RECORDING_DISABLED,
                        "事件记录已关闭，没有可回放的事件序列");
            }
            if (eventLog.truncated()) {
                throw new ReplayRejectedException(ReplayRejectedException.LOG_TRUNCATED,
                        "事件记录达到容量上限后丢弃过新事件（已丢 " + eventLog.droppedNew()
                                + " 条），记录不完整，拒绝回放");
            }
            log = eventLog.snapshot();
            maxSeq = eventLog.lastSeq();
        }
        if (log.isEmpty()) {
            throw new ReplayRejectedException(ReplayRejectedException.EMPTY_LOG,
                    "尚未记录任何事件，无法回放");
        }
        long from = fromSeq == null ? firstBeginSeq(log) : fromSeq;
        long to;
        if (toSeq == null) {
            // 全量回放：自动取已记录事件中的最后一个确定边界（命令或 TickEnd），
            // 避免在线仿真恰好追加到某个 tick 中途时把终点落在观测事件上。
            to = lastBoundarySeq(log);
        } else {
            to = toSeq;
        }
        if (from < 1L || to < 1L) {
            throw new ReplayRejectedException(ReplayRejectedException.OUT_OF_RANGE,
                    "事件序号必须 >= 1: from=" + from + ", to=" + to);
        }
        if (to < from) {
            throw new ReplayRejectedException(ReplayRejectedException.INVALID_RANGE,
                    "非法事件范围：起点不能大于终点: from=" + from + ", to=" + to);
        }
        if (to > maxSeq) {
            throw new ReplayRejectedException(ReplayRejectedException.OUT_OF_RANGE,
                    "事件终点超出已记录范围: to=" + to + ", 已记录最大序号=" + maxSeq);
        }
        SimEvent endEvent = eventAt(log, to);
        if (endEvent == null) {
            throw new ReplayRejectedException(ReplayRejectedException.OUT_OF_RANGE,
                    "终点序号 " + to + " 不在已保留事件中（可能已被容量策略丢弃）");
        }
        if (!isBoundary(endEvent)) {
            throw new ReplayRejectedException(ReplayRejectedException.NOT_AT_BOUNDARY,
                    "终点序号 " + to + " 落在一个 tick 的观测事件内部（"
                            + endEvent.getClass().getSimpleName()
                            + "），回放范围终点必须是命令事件或 TickEnd");
        }
        if (eventAt(log, from) == null) {
            throw new ReplayRejectedException(ReplayRejectedException.OUT_OF_RANGE,
                    "起点序号 " + from + " 不在已保留事件中");
        }
        // 找到不晚于 from 的最近一次 Begin 作为隔离重建起点
        long rebuildFrom = firstBeginSeq(log);
        for (SimEvent e : log) {
            if (e.seq() > from) {
                break;
            }
            if (e instanceof SimEvent.Begin) {
                rebuildFrom = e.seq();
            }
        }

        ReplayEngine engine = new ReplayEngine(log, rebuildFrom, from, to);
        SimulationView endSnapshot = engine.run();

        List<EventView> slice = new ArrayList<>();
        for (SimEvent e : log) {
            if (e.seq() >= from && e.seq() <= to) {
                slice.add(EventView.of(e));
            }
        }
        return new ReplayResult(endSnapshot, List.copyOf(slice), from, to, rebuildFrom, true);
    }

    private static long firstBeginSeq(List<SimEvent> log) {
        for (SimEvent e : log) {
            if (e instanceof SimEvent.Begin) {
                return e.seq();
            }
        }
        return log.get(0).seq();
    }

    private static long lastBoundarySeq(List<SimEvent> log) {
        for (int i = log.size() - 1; i >= 0; i--) {
            SimEvent e = log.get(i);
            if (isBoundary(e)) {
                return e.seq();
            }
        }
        return log.get(log.size() - 1).seq();
    }

    private static SimEvent eventAt(List<SimEvent> log, long seq) {
        for (SimEvent e : log) {
            if (e.seq() == seq) {
                return e;
            }
            if (e.seq() > seq) {
                return null;
            }
        }
        return null;
    }

    private static boolean isBoundary(SimEvent e) {
        return e instanceof SimEvent.Begin
                || e instanceof SimEvent.CmdTick
                || e instanceof SimEvent.TickEnd
                || e instanceof SimEvent.CmdSwitch
                || e instanceof SimEvent.CmdPort
                || e instanceof SimEvent.CmdPortEnabled
                || e instanceof SimEvent.CmdPortConfig
                || e instanceof SimEvent.CmdFault
                || e instanceof SimEvent.CmdVlan
                || e instanceof SimEvent.CmdHost
                || e instanceof SimEvent.CmdHostConnect
                || e instanceof SimEvent.CmdHostDisconnect
                || e instanceof SimEvent.CmdLink
                || e instanceof SimEvent.CmdLinkRemove
                || e instanceof SimEvent.CmdInject;
    }

    public synchronized String addSwitch(String name, SwitchConfig config) {
        requireNotExists(name);
        SwitchDevice device = new SwitchDevice(name, config);
        if (faultChanceOverride != null) {
            device.setFaultChance(faultChanceOverride);
        }
        switches.put(name, device);
        recordEvent((seq, t) -> new SimEvent.CmdSwitch(seq, t, name, config.agingTicks(), config.seed()));
        return name;
    }

    public synchronized int addPort(String switchName, boolean hostFacing, PortConfig config) {
        SwitchDevice s = sw(switchName);
        int id = s.addPort(config, hostFacing);
        recordEvent((seq, t) -> new SimEvent.CmdPort(seq, t, switchName, id, hostFacing, config));
        return id;
    }

    public synchronized void setPortEnabled(String switchName, int portId, boolean enabled) {
        sw(switchName).setPortEnabled(portId, enabled, sinkFor(switchName));
        recordEvent((seq, t) -> new SimEvent.CmdPortEnabled(seq, t, switchName, portId, enabled));
    }

    public synchronized void configurePort(String switchName, int portId, PortConfig config) {
        sw(switchName).configurePort(portId, config);
        recordEvent((seq, t) -> new SimEvent.CmdPortConfig(seq, t, switchName, portId, config));
    }

    public synchronized void setFault(String switchName, int portId, PortFault fault) {
        PortFault f = fault == null ? PortFault.none() : fault;
        sw(switchName).setFault(portId, f);
        recordEvent((seq, t) -> new SimEvent.CmdFault(seq, t, switchName, portId, f));
    }

    public synchronized void setVlan(String switchName, int portId, int vlan) {
        sw(switchName).setVlan(portId, vlan);
        recordEvent((seq, t) -> new SimEvent.CmdVlan(seq, t, switchName, portId, vlan));
    }

    public synchronized String addHost(String name, String mac) {
        requireHostNotExists(name);
        hosts.put(name, new Host(name, MacAddress.of(mac)));
        recordEvent((seq, t) -> new SimEvent.CmdHost(seq, t, name, mac));
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
        recordEvent((seq, t) -> new SimEvent.CmdHostConnect(seq, t, hostName, switchName, portId));
    }

    public synchronized void disconnectHost(String hostName) {
        Host h = host(hostName);
        detachFromCurrent(h);
        h.detach();
        recordEvent((seq, t) -> new SimEvent.CmdHostDisconnect(seq, t, hostName));
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
        recordEvent((seq, t) -> new SimEvent.CmdLink(seq, t, switchA, portA, switchB, portB));
    }

    public synchronized void removeLink(String switchA, int portA, String switchB, int portB) {
        topology.removeLink(switchA, portA, switchB, portB);
        reconverge();
        recordEvent((seq, t) -> new SimEvent.CmdLinkRemove(seq, t, switchA, portA, switchB, portB));
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
        Frame frame = new Frame(h.mac(), MacAddress.of(dstMac), vlan, priority, payload);
        long frameId = frame.id();
        recordEvent((seq, t) -> new SimEvent.CmdInject(seq, t, hostName, frame, frameId));
        injected++;
        SwitchDevice s = switches.get(loc.switchName());
        // 入端拒绝/接收的计数与观测事件统一由 admitIngress 经 sink 处理，只发生一次
        s.admitIngress(loc.portId(), frame, sinkFor(loc.switchName()));
    }

    /** 推进一个 tick：老化 → 入处理 → 出处理。 */
    public synchronized void tick() {
        recordEvent(SimEvent.CmdTick::new);
        advanceOneTick();
        recordEvent(SimEvent.TickEnd::new);
    }

    private void advanceOneTick() {
        tick++;
        for (SwitchDevice s : switches.values()) {
            s.ageFdb(tick);
        }
        for (SwitchDevice s : switches.values()) {
            s.processIngress(tick, sinkFor(s.name()));
        }
        for (SwitchDevice s : switches.values()) {
            final String switchName = s.name();
            SwitchDevice.Transmitter transmitter = (egressPortId, frame) ->
                    transmit(switchName, egressPortId, frame);
            s.processEgress(tick, transmitter);
        }
    }

    /** 回放用注入：帧使用原运行的全局 ID，保证观测事件里的帧标识逐次一致。 */
    synchronized void injectReplay(String hostName, Frame recorded, long frameId) {
        Host h = host(hostName);
        HostView loc = h.snapshot();
        if (loc.switchName() == null) {
            throw new IllegalStateException("主机未接入交换机: " + hostName);
        }
        Frame frame = new Frame(recorded.src(), recorded.dst(), recorded.vlan(),
                recorded.priority(), recorded.payload(), frameId);
        recordEvent((seq, t) -> new SimEvent.CmdInject(seq, t, hostName, frame, frameId));
        injected++;
        SwitchDevice s = switches.get(loc.switchName());
        s.admitIngress(loc.portId(), frame, sinkFor(loc.switchName()));
    }

    /** 把出端口帧投递到下挂主机或邻居交换机的入端口。 */
    private DropReason transmit(String switchName, int portId, Frame frame) {
        SwitchDevice s = switches.get(switchName);
        SwitchView view = s.snapshot(tick);
        var pv = view.ports().get(portId);
        s.countTx(portId);
        recordEvent((seq, t) -> new SimEvent.FrameTx(seq, t, switchName, portId, frame.id(),
                pv.hostFacing()));
        if (pv.hostFacing() && pv.attachedHost() != null) {
            Host h = hosts.get(pv.attachedHost());
            if (h != null) {
                h.receive(frame);
                hostDelivered++;
                final String hostName = pv.attachedHost();
                recordEvent((seq, t) -> new SimEvent.FrameDelivered(seq, t, switchName, portId,
                        frame.id(), hostName));
            }
            return null;
        }
        var neighbor = topology.neighbor(switchName, portId);
        if (neighbor.isEmpty()) {
            return null;
        }
        Topology.Endpoint ep = neighbor.get();
        SwitchDevice ns = switches.get(ep.switchName());
        // 帧进入邻居入队列；接收/拒绝的计数与事件由邻居侧 sink 统一处理，只发生一次。
        // 发送端只计 tx（已在 processEgress 计），绝不因邻居拒绝而重复计数。
        ns.admitIngress(ep.portId(), frame, sinkFor(ep.switchName()));
        return null;
    }

    /** 构造某台交换机的观测事件汇：计数与事件记录都在此统一、且只发生一次。 */
    private SwitchDevice.EventSink sinkFor(String switchName) {
        return new SwitchDevice.EventSink() {
            @Override
            public void admitted(int portId, Frame frame) {
                recordEvent((seq, t) -> new SimEvent.FrameAdmitted(seq, t,
                        switchName, portId, frame.id()));
            }

            @Override
            public void rejected(int portId, DropReason reason, Frame frame) {
                recordEvent((seq, t) -> new SimEvent.FrameRejected(seq, t,
                        switchName, portId, frame.id(), reason));
                recordDrop(reason, frame, switchName, portId);
            }

            @Override
            public void faultVerdict(int portId, Frame frame, boolean dropDrawn,
                                     boolean reorderDrawn) {
                var pv = switches.get(switchName).snapshot(tick).ports().get(portId);
                double dropRate = pv.fault().dropRate();
                boolean reorderEnabled = pv.fault().reorderEnabled();
                recordEvent((seq, t) -> new SimEvent.FaultVerdict(seq, t, switchName, portId,
                        frame.id(), dropRate, reorderEnabled, dropDrawn, reorderDrawn));
            }

            @Override
            public void dropped(int portId, DropReason reason, Frame frame) {
                recordEvent((seq, t) -> new SimEvent.FrameDrop(seq, t,
                        switchName, portId, frame.id(), reason));
                recordDrop(reason, frame, switchName, portId);
            }

            @Override
            public void flooded(int portId, Frame frame) {
                recordEvent((seq, t) -> new SimEvent.FrameFlooded(seq, t,
                        switchName, portId, frame.id()));
            }

            @Override
            public void learned(int portId, Frame frame) {
                recordEvent((seq, t) -> new SimEvent.FrameLearned(seq, t,
                        switchName, portId, frame.id()));
            }

            @Override
            public void deferred(int portId, Frame frame, String cause) {
                recordEvent((seq, t) -> new SimEvent.FrameDeferred(seq, t,
                        switchName, portId, frame.id(), cause));
            }
        };
    }

    private void recordDrop(DropReason reason, Frame frame, String switchName, int portId) {
        dropEvents.add(new DropEvent(tick, switchName, portId, reason, frame, frame.id()));
        dropReasonCounts.merge(reason, 1L, Long::sum);
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
        long arrivals = 0;
        long rejected = 0;
        long filtered = 0;
        for (SwitchDevice s : switches.values()) {
            SwitchView v = s.snapshot(tick);
            switchViews.add(v);
            rx += v.totals().getOrDefault("rxFrames", 0L);
            tx += v.totals().getOrDefault("txFrames", 0L);
            learned += v.totals().getOrDefault("learned", 0L);
            flooded += v.totals().getOrDefault("flooded", 0L);
            dropped += v.totals().getOrDefault("dropped", 0L);
            arrivals += v.totals().getOrDefault("ingressArrivals", 0L);
            rejected += v.totals().getOrDefault("admissionRejected", 0L);
            filtered += v.totals().getOrDefault("filtered", 0L);
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
        totals.put("ingressArrivals", arrivals);
        totals.put("admissionRejected", rejected);
        totals.put("filtered", filtered);
        totals.put("dropEvents", (long) dropEvents.size());
        return new SimulationView(tick, List.copyOf(switchViews), List.copyOf(hostViews),
                topology.linksView(), List.copyOf(blockedPorts),
                Map.copyOf(reasons), Map.copyOf(totals));
    }

    public synchronized void reset() {
        reset(globalSeed, EventLogOptions.defaults());
    }

    /** 重置并允许指定新的事件记录选项（供控制器 reset 透传）。 */
    public synchronized void reset(long seed, EventLogOptions options) {
        this.tick = 0;
        this.switches.clear();
        this.hosts.clear();
        this.topology.clear();
        // 重置为在线记录器与真实随机故障源，避免遗留回放钩子
        this.faultChanceOverride = null;
        this.eventLog.reconfigure(options == null ? EventLogOptions.defaults() : options);
        this.recorder = f -> eventLog.record(this::now, f);
        this.dropEvents.clear();
        this.dropReasonCounts.clear();
        for (DropReason r : DropReason.values()) {
            this.dropReasonCounts.put(r, 0L);
        }
        this.injected = 0;
        this.hostDelivered = 0;
        recordEvent((seq, t) -> new SimEvent.Begin(seq, t, seed));
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
