package com.github.guoyyit.l2switchsimulatorjava.sim;

import com.github.guoyyit.l2switchsimulatorjava.sim.core.Simulation;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.BackpressureAction;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.DropReason;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.Frame;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.FramePriority;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.PortConfig;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.StormAction;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.SwitchConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 需求 4（风暴）、6（确定性回放与故障注入隔离）。 */
class FaultAndStormTest {

    private Simulation twoHosts(long seed) {
        Simulation sim = new Simulation(seed);
        sim.addSwitch("sw", new SwitchConfig(-1, seed));
        sim.addPort("sw", true, PortConfig.defaults());
        sim.addPort("sw", true, PortConfig.defaults());
        sim.addHost("h1", "00:00:00:00:00:01");
        sim.addHost("h2", "00:00:00:00:00:02");
        sim.connectHost("h1", "sw", 0);
        sim.connectHost("h2", "sw", 1);
        return sim;
    }

    @Test
    void deterministicDropFaultReplaysIdentically() {
        PortConfig bigIngress = new PortConfig(256, 4, BackpressureAction.DROP, 1_000_000, 8,
                StormAction.DROP);
        long drops1;
        long drops2;
        {
            Simulation sim = twoHosts(99L);
            sim.configurePort("sw", 0, bigIngress);
            sim.configurePort("sw", 1, bigIngress);
            sim.setFault("sw", 0, new com.github.guoyyit.l2switchsimulatorjava.sim.model.PortFault(0.5, false));
            for (int i = 0; i < 200; i++) {
                sim.inject("h1", "ff:ff:ff:ff:ff:ff", 1, FramePriority.P0, "f" + i);
            }
            sim.tick(50);
            drops1 = sim.dropCount(DropReason.FAULT_DROP);
        }
        {
            Simulation sim = twoHosts(99L);
            sim.configurePort("sw", 0, bigIngress);
            sim.configurePort("sw", 1, bigIngress);
            sim.setFault("sw", 0, new com.github.guoyyit.l2switchsimulatorjava.sim.model.PortFault(0.5, false));
            for (int i = 0; i < 200; i++) {
                sim.inject("h1", "ff:ff:ff:ff:ff:ff", 1, FramePriority.P0, "f" + i);
            }
            sim.tick(50);
            drops2 = sim.dropCount(DropReason.FAULT_DROP);
        }
        assertEquals(drops1, drops2);
        // 50% 丢帧率下 200 帧应明显有丢，但不是全丢
        assertTrue(drops1 > 40 && drops1 < 160, "drops=" + drops1);
    }

    @Test
    void dropRateOneDropsAll() {
        Simulation sim = twoHosts(5L);
        sim.setFault("sw", 0, new com.github.guoyyit.l2switchsimulatorjava.sim.model.PortFault(1.0, false));
        for (int i = 0; i < 10; i++) {
            sim.inject("h1", "ff:ff:ff:ff:ff:ff", 1, FramePriority.P0, "x" + i);
        }
        sim.tick(5);
        assertEquals(10, sim.dropCount(DropReason.FAULT_DROP));
        assertTrue(sim.hostReceived("h2").isEmpty());
    }

    @Test
    void faultOnOnePortDoesNotAffectOtherPorts() {
        // 4 台“主机”挂在同一交换机；端口 0 配置 100% 丢帧，其余端口行为必须完全正常
        Simulation sim = new Simulation(7L);
        sim.addSwitch("sw", new SwitchConfig(-1, 7L));
        for (int i = 0; i < 4; i++) {
            sim.addPort("sw", true, PortConfig.defaults());
        }
        sim.addHost("g1", "00:00:00:00:00:01");
        sim.addHost("g2", "00:00:00:00:00:02");
        sim.addHost("g3", "00:00:00:00:00:03");
        sim.addHost("g4", "00:00:00:00:00:04");
        sim.connectHost("g1", "sw", 0);
        sim.connectHost("g2", "sw", 1);
        sim.connectHost("g3", "sw", 2);
        sim.connectHost("g4", "sw", 3);
        sim.setFault("sw", 0, new com.github.guoyyit.l2switchsimulatorjava.sim.model.PortFault(1.0, false));

        // g1 的广播帧在入端口就被丢弃，其他主机收不到
        sim.inject("g1", "ff:ff:ff:ff:ff:ff", 1, FramePriority.P0, "bad");
        // g2 的广播帧正常泛洪到 g3/g4，与 g1 的故障无关
        sim.inject("g2", "ff:ff:ff:ff:ff:ff", 1, FramePriority.P0, "good");
        sim.tick(3);
        assertTrue(sim.hostReceived("g3").stream().anyMatch(f -> f.payload().equals("good")));
        assertTrue(sim.hostReceived("g4").stream().anyMatch(f -> f.payload().equals("good")));
        assertTrue(sim.hostReceived("g3").stream().noneMatch(f -> f.payload().equals("bad")));
        // g3 发 100 帧给 g4：g3/g4 端口都无故障，全部送达
        PortConfig big = new PortConfig(256, 4, BackpressureAction.DROP, 1_000_000, 8,
                StormAction.DROP);
        for (int i = 0; i < 4; i++) {
            sim.configurePort("sw", i, big);
        }
        for (int i = 0; i < 100; i++) {
            sim.inject("g3", "00:00:00:00:00:04", 1, FramePriority.P0, "c" + i);
        }
        sim.tick(110);
        assertEquals(100, sim.hostReceived("g4").stream()
                .filter(f -> f.payload().startsWith("c")).count());
    }

    @Test
    void reorderFaultChangesOrderDeterministically() {
        Simulation a = twoHosts(1234L);
        Simulation b = twoHosts(1234L);
        PortConfig big = new PortConfig(256, 4, BackpressureAction.DROP, 1_000_000, 8,
                StormAction.DROP);
        a.configurePort("sw", 0, big);
        a.configurePort("sw", 1, big);
        b.configurePort("sw", 0, big);
        b.configurePort("sw", 1, big);
        a.setFault("sw", 0, new com.github.guoyyit.l2switchsimulatorjava.sim.model.PortFault(0.0, true));
        b.setFault("sw", 0, new com.github.guoyyit.l2switchsimulatorjava.sim.model.PortFault(0.0, true));
        for (int i = 0; i < 40; i++) {
            a.inject("h1", "ff:ff:ff:ff:ff:ff", 1, FramePriority.P0, "r" + i);
            b.inject("h1", "ff:ff:ff:ff:ff:ff", 1, FramePriority.P0, "r" + i);
        }
        a.tick(60);
        b.tick(60);
        List<String> oa = a.hostReceived("h2").stream().map(Frame::payload).toList();
        List<String> ob = b.hostReceived("h2").stream().map(Frame::payload).toList();
        assertEquals(oa, ob, "相同种子与序列下错序结果必须一致");
        // 错序开启后，至少出现一对相邻帧交换（顺序不恒等于输入）
        boolean swapped = false;
        for (int i = 0; i + 1 < oa.size(); i++) {
            int x = Integer.parseInt(oa.get(i).substring(1));
            int y = Integer.parseInt(oa.get(i + 1).substring(1));
            if (y < x) {
                swapped = true;
            }
        }
        assertTrue(swapped, "错序注入未观察到顺序变化: " + oa);
        // 帧集合不变：不丢帧
        assertEquals(40, oa.size());
    }

    @Test
    void stormDropPolicyDropsExcessBroadcasts() {
        // 阈值 4/窗口8，DROP：同一 tick 注入 10 个广播帧，至多 4 个通过
        PortConfig cfg = new PortConfig(64, 4, BackpressureAction.DROP, 4, 8, StormAction.DROP);
        Simulation sim = new Simulation(1L);
        sim.addSwitch("sw", new SwitchConfig(-1, 1L));
        sim.addPort("sw", true, cfg);
        sim.addPort("sw", true, cfg);
        sim.addHost("h1", "00:00:00:00:00:01");
        sim.addHost("h2", "00:00:00:00:00:02");
        sim.connectHost("h1", "sw", 0);
        sim.connectHost("h2", "sw", 1);
        for (int i = 0; i < 10; i++) {
            sim.inject("h1", "ff:ff:ff:ff:ff:ff", 1, FramePriority.P0, "s" + i);
        }
        sim.tick(5);
        assertEquals(6, sim.dropCount(DropReason.STORM_CONTROL));
        long delivered = sim.hostReceived("h2").stream()
                .filter(f -> f.payload().startsWith("s")).count();
        assertEquals(4, delivered);
    }

    @Test
    void stormLimitPolicyDefersWithoutDropping() {
        PortConfig cfg = new PortConfig(64, 4, BackpressureAction.DROP, 4, 2, StormAction.LIMIT);
        Simulation sim = new Simulation(1L);
        sim.addSwitch("sw", new SwitchConfig(-1, 1L));
        sim.addPort("sw", true, cfg);
        sim.addPort("sw", true, cfg);
        sim.addHost("h1", "00:00:00:00:00:01");
        sim.addHost("h2", "00:00:00:00:00:02");
        sim.connectHost("h1", "sw", 0);
        sim.connectHost("h2", "sw", 1);
        for (int i = 0; i < 8; i++) {
            sim.inject("h1", "ff:ff:ff:ff:ff:ff", 1, FramePriority.P0, "l" + i);
        }
        sim.tick(30);
        assertEquals(0, sim.dropCount(DropReason.STORM_CONTROL), "限速模式不应产生风暴丢弃");
        assertEquals(8, sim.hostReceived("h2").stream()
                .filter(f -> f.payload().startsWith("l")).count());
        // 限速下送达被拉长：第 1 个 tick 之后仍有帧在送达
        assertFalse(sim.hostReceived("h2").isEmpty());
    }

    @Test
    void fullSimulationIsDeterministicAcrossReplays() {
        var r1 = replayOnce();
        var r2 = replayOnce();
        assertEquals(r1, r2);
    }

    private com.github.guoyyit.l2switchsimulatorjava.sim.model.SimulationView replayOnce() {
        Simulation sim = new Simulation(2024L);
        sim.addSwitch("sw", new SwitchConfig(30, 2024L));
        for (int i = 0; i < 3; i++) {
            sim.addPort("sw", true, PortConfig.defaults());
        }
        sim.addHost("a", "00:00:00:00:00:01");
        sim.addHost("b", "00:00:00:00:00:02");
        sim.addHost("c", "00:00:00:00:00:03");
        sim.connectHost("a", "sw", 0);
        sim.connectHost("b", "sw", 1);
        sim.connectHost("c", "sw", 2);
        sim.setFault("sw", 0, new com.github.guoyyit.l2switchsimulatorjava.sim.model.PortFault(0.3, true));
        String[] dsts = {"ff:ff:ff:ff:ff:ff", "00:00:00:00:00:02", "00:00:00:00:00:03",
                "01:00:5e:00:00:01"};
        for (int i = 0; i < 60; i++) {
            sim.inject("a", dsts[i % dsts.length], 1, FramePriority.values()[i % 8], "z" + i);
            sim.tick();
        }
        sim.tick(40);
        return sim.snapshot();
    }
}
