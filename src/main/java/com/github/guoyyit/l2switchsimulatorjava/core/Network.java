package com.github.guoyyit.l2switchsimulatorjava.core;

import com.github.guoyyit.l2switchsimulatorjava.model.Frame;
import com.github.guoyyit.l2switchsimulatorjava.port.BackpressureMode;
import com.github.guoyyit.l2switchsimulatorjava.port.FaultProfile;
import com.github.guoyyit.l2switchsimulatorjava.storm.StormConfig;
import com.github.guoyyit.l2switchsimulatorjava.storm.StormController;
import com.github.guoyyit.l2switchsimulatorjava.topology.Link;
import com.github.guoyyit.l2switchsimulatorjava.topology.Topology;
import com.github.guoyyit.l2switchsimulatorjava.stp.StpEngine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 仿真网络：管理多台交换机、拓扑、虚拟时钟、STP 阻断集合、事件日志与全局随机种子。
 *
 * 所有公共方法均在内部锁上串行执行，保证并发查询不会观察到转发处理的部分更新。
 * 处理顺序在单 tick 内固定：注入 -> fabric 口队列交付（可能引发对端再转发）
 * -> 老化 -> 时钟推进，结果可由相同种子与事件序列逐次复现。
 */
public final class Network {

    private final Topology topology;
    private final StpEngine stp;
    private final SimulationClock clock;
    private final DeterministicRandom rng;
    private final long seed;
    private final Map<String, Switch> switches = new LinkedHashMap<>();
    private final List<SimulationEvent> eventLog = new ArrayList<>();
    private Set<StpEngine.BlockedPort> blockedPorts = new TreeSet<>();

    public Network(long seed) {
        this.seed = seed;
        this.topology = new Topology();
        this.stp = new StpEngine();
        this.clock = new SimulationClock();
        this.rng = new DeterministicRandom(seed ^ 0x5DEECE66DL);
    }

    public long getSeed() { return seed; }
    public SimulationClock clock() { return clock; }
    public Topology topology() { return topology; }

    public synchronized Switch addSwitch(String name, int portCount, SwitchConfig config) {
        if (switches.containsKey(name)) {
            throw new IllegalArgumentException("switch exists: " + name);
        }
        topology.addSwitch(name);
        Switch sw = new Switch(name, portCount,
                config == null ? new SwitchConfig() : config,
                new DeterministicRandom(rng.nextLong() ^ name.hashCode()));
        sw.setFabricDelivery(this::deliverFabric);
        switches.put(name, sw);
        return sw;
    }

    public synchronized Switch getSwitch(String name) {
        Switch sw = switches.get(name);
        if (sw == null) {
            throw new IllegalArgumentException("unknown switch: " + name);
        }
        return sw;
    }

    public synchronized Collection<Switch> switches() {
        return new ArrayList<>(switches.values());
    }

    /** 在两台交换机的指定端口间建立互联链路，两端口成为 trunk（fabric）口，VLAN 取 0（全局默认域）。 */
    public synchronized void addLink(String switchA, int portA, String switchB, int portB) {
        addLink(switchA, portA, switchB, portB, 0);
    }

    public synchronized void addLink(String switchA, int portA, String switchB, int portB, int vlan) {
        Switch a = getSwitch(switchA);
        Switch b = getSwitch(switchB);
        Link link = new Link(switchA, portA, switchB, portB);
        topology.addLink(link);
        a.markFabricPort(link.portA(), vlan);
        b.markFabricPort(link.portB(), vlan);
        recomputeStp();
    }

    /** 重新计算生成树并把阻断集合下发到各交换机。 */
    public synchronized void recomputeStp() {
        blockedPorts = stp.recompute(topology.switches(), topology.links());
        for (Switch sw : switches.values()) {
            Set<Integer> perSwitch = new TreeSet<>();
            for (StpEngine.BlockedPort bp : blockedPorts) {
                if (bp.switchName().equals(sw.getName())) {
                    perSwitch.add(bp.portIndex());
                }
            }
            sw.applyBlockedPorts(perSwitch);
        }
    }

    public synchronized Set<StpEngine.BlockedPort> blockedPorts() {
        return new TreeSet<>(blockedPorts);
    }

    /** 主机向端口注入帧并立即处理（当前 tick）。 */
    public synchronized void inject(String switchName, int portIndex, Frame frame) {
        Switch sw = getSwitch(switchName);
        long now = clock.now();
        eventLog.add(SimulationEvent.inject(now, switchName, portIndex, frame));
        sw.receiveFromHost(portIndex, frame, now);
        drainAllFabric(now);
    }

    /** fabric 投递回调：把帧送入对端交换机的 fabric 口。 */
    private void deliverFabric(Switch.FabricDelivery delivery) {
        // 在调用方锁内执行（Network 为可重入 synchronized）。
        Link link = findLink(delivery.fromSwitch(), delivery.fromPort());
        if (link == null) {
            return;
        }
        Link.Endpoint other = link.other(delivery.fromSwitch(), delivery.fromPort());
        Switch peer = switches.get(other.switchName());
        if (peer == null) {
            return;
        }
        peer.receiveFromFabric(other.portIndex(), delivery.frame(), clock.now());
    }

    private Link findLink(String switchName, int portIndex) {
        for (Link link : topology.links()) {
            if (link.other(switchName, portIndex) != null) {
                return link;
            }
        }
        return null;
    }

    /** 排空所有 fabric 口输出队列；对端再转发可能产生新的 fabric 帧，迭代到稳定。 */
    private void drainAllFabric(long now) {
        boolean progressed = true;
        int guard = 0;
        int maxIterations = switches.values().stream().mapToInt(s -> s.getPorts().size()).sum() + 4;
        while (progressed && guard++ <= maxIterations * 64) {
            progressed = false;
            for (Switch sw : switches.values()) {
                for (int p = 0; p < sw.getPorts().size(); p++) {
                    if (sw.getPorts().get(p).getConfig().isFabricPort()
                            && !sw.getPorts().get(p).getOutputQueue().isEmpty()) {
                        int n = sw.drainFabricPort(p, now);
                        if (n > 0) {
                            progressed = true;
                        }
                    }
                }
            }
        }
    }

    /** 推进时钟一个 tick：先推进，再做老化与限速时钟更新，最后排空所有 fabric 队列。 */
    public synchronized void step() {
        eventLog.add(SimulationEvent.tick(clock.now()));
        clock.tick();
        long now = clock.now();
        for (Switch sw : switches.values()) {
            sw.age(now);
        }
        drainAllFabric(now);
    }

    /** 将所有主机口输出队列的帧交付给主机（出方向故障在此生效）。 */
    public synchronized int drainHosts() {
        long now = clock.now();
        eventLog.add(SimulationEvent.drain(now));
        int total = 0;
        for (Switch sw : switches.values()) {
            for (int p = 0; p < sw.getPorts().size(); p++) {
                if (!sw.getPorts().get(p).getConfig().isFabricPort()) {
                    total += sw.drainHostPort(p, now);
                }
            }
        }
        drainAllFabric(now);
        return total;
    }

    /** 读取某主机口当前输出队列的帧快照（不消费，不触发出方向故障）。 */
    public synchronized List<Frame> peekHostQueue(String switchName, int portIndex) {
        Switch sw = getSwitch(switchName);
        return new ArrayList<>(sw.port(portIndex).getOutputQueue().snapshotByPriority());
    }

    public synchronized void enablePort(String switchName, int portIndex) {
        getSwitch(switchName).enablePort(portIndex);
    }

    public synchronized void disablePort(String switchName, int portIndex) {
        getSwitch(switchName).disablePort(portIndex);
    }

    public synchronized void changePortVlan(String switchName, int portIndex, int vlan) {
        getSwitch(switchName).changePortVlan(portIndex, vlan);
    }

    /** 统一更新某交换机所有端口的风暴控制配置（更新即重置窗口，行为确定）。 */
    public synchronized void configureStorm(String switchName, StormConfig stormConfig) {
        Switch sw = getSwitch(switchName);
        for (int p = 0; p < sw.getPorts().size(); p++) {
            StormController controller = sw.stormController(p);
            controller.getConfig().setThreshold(stormConfig.getThreshold());
            controller.getConfig().setWindowTicks(stormConfig.getWindowTicks());
            controller.getConfig().setMode(stormConfig.getMode());
            controller.reset();
        }
    }

    public synchronized void setBackpressureMode(String switchName, BackpressureMode mode) {
        Switch sw = getSwitch(switchName);
        for (var port : sw.getPorts()) {
            port.getConfig().setBackpressureMode(mode);
        }
    }

    public synchronized void setFault(String switchName, int portIndex, double dropP, double oooP) {
        getSwitch(switchName).port(portIndex).setFaultProfile(new FaultProfile(dropP, oooP));
    }

    public synchronized List<DropEventView> recentDrops() {
        List<DropEventView> all = new ArrayList<>();
        for (Switch sw : switches.values()) {
            all.addAll(sw.recentDrops());
        }
        all.sort((a, b) -> a.tick() != b.tick() ? Long.compare(a.tick(), b.tick())
                : a.switchName().compareTo(b.switchName()));
        return all;
    }

    public synchronized void clearDropLog() {
        for (Switch sw : switches.values()) {
            sw.clearDropLog();
        }
    }

    public synchronized NetworkView snapshot() {
        long now = clock.now();
        List<SwitchView> views = new ArrayList<>();
        for (Switch sw : switches.values()) {
            views.add(sw.snapshot(now));
        }
        List<String> blocked = new ArrayList<>();
        for (StpEngine.BlockedPort bp : blockedPorts) {
            blocked.add(bp.toString());
        }
        return new NetworkView(now, Collections.unmodifiableList(views),
                topology.links(), Collections.unmodifiableList(blocked), recentDrops());
    }

    public synchronized List<SimulationEvent> eventLog() {
        return new ArrayList<>(eventLog);
    }
}
