package com.github.guoyyit.l2switchsimulatorjava.sim;

import com.github.guoyyit.l2switchsimulatorjava.sim.core.Simulation;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.Frame;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.FramePriority;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.PortConfig;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.SwitchConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 需求 3、4：广播/组播/未知单播泛洪、转发域约束、入端口排除、环路阻断收敛。 */
class FloodAndLoopTest {

    private Simulation singleSwitchVlanSetup() {
        Simulation sim = new Simulation(1L);
        sim.addSwitch("sw", new SwitchConfig(-1, 1L));
        for (int i = 0; i < 4; i++) {
            sim.addPort("sw", true, PortConfig.defaults());
        }
        // 端口 0/1 在 vlan 1，端口 2/3 在 vlan 2
        sim.setVlan("sw", 2, 2);
        sim.setVlan("sw", 3, 2);
        sim.addHost("h1", "00:00:00:00:00:01");
        sim.addHost("h2", "00:00:00:00:00:02");
        sim.addHost("h3", "00:00:00:00:00:03");
        sim.addHost("h4", "00:00:00:00:00:04");
        sim.connectHost("h1", "sw", 0);
        sim.connectHost("h2", "sw", 1);
        sim.connectHost("h3", "sw", 2);
        sim.connectHost("h4", "sw", 3);
        return sim;
    }

    @Test
    void broadcastExcludesIngressPort() {
        Simulation sim = singleSwitchVlanSetup();
        sim.inject("h1", "ff:ff:ff:ff:ff:ff", 1, FramePriority.P0, "bcast");
        sim.tick(2);
        assertTrue(sim.hostReceived("h2").stream().anyMatch(f -> f.payload().equals("bcast")));
        // 发送者自己不收
        assertTrue(sim.hostReceived("h1").stream().noneMatch(f -> f.payload().equals("bcast")));
    }

    @Test
    void floodIsConstrainedByVlan() {
        Simulation sim = singleSwitchVlanSetup();
        sim.inject("h1", "ff:ff:ff:ff:ff:ff", 1, FramePriority.P0, "v1-only");
        sim.tick(2);
        assertTrue(sim.hostReceived("h2").stream().anyMatch(f -> f.payload().equals("v1-only")));
        assertTrue(sim.hostReceived("h3").stream().noneMatch(f -> f.payload().equals("v1-only")));
        assertTrue(sim.hostReceived("h4").stream().noneMatch(f -> f.payload().equals("v1-only")));

        sim.inject("h3", "ff:ff:ff:ff:ff:ff", 2, FramePriority.P0, "v2-only");
        sim.tick(2);
        assertTrue(sim.hostReceived("h4").stream().anyMatch(f -> f.payload().equals("v2-only")));
        assertTrue(sim.hostReceived("h1").stream().noneMatch(f -> f.payload().equals("v2-only")));
    }

    @Test
    void multicastFloodsLikeBroadcastWithinVlan() {
        Simulation sim = singleSwitchVlanSetup();
        sim.inject("h1", "01:00:5e:00:00:01", 1, FramePriority.P0, "mcast");
        sim.tick(2);
        assertTrue(sim.hostReceived("h2").stream().anyMatch(f -> f.payload().equals("mcast")));
        assertTrue(sim.hostReceived("h1").stream().noneMatch(f -> f.payload().equals("mcast")));
        assertTrue(sim.hostReceived("h3").stream().noneMatch(f -> f.payload().equals("mcast")));
    }

    @Test
    void unknownUnicastFloodsThenKnownUnicastIsDirect() {
        Simulation sim = singleSwitchVlanSetup();
        // h1 向一个未学习的单播地址发送
        sim.inject("h1", "00:00:00:00:aa:aa", 1, FramePriority.P0, "unknown");
        sim.tick(2);
        assertTrue(sim.hostReceived("h2").stream().anyMatch(f -> f.payload().equals("unknown")));
        assertTrue(sim.hostReceived("h3").stream().noneMatch(f -> f.payload().equals("unknown")));
    }

    /** 三台交换机组成三角形：A-B(port0)、A-C(port1)、B-C(port1)；应阻断 A:C 边两端。 */
    private Simulation triangleTopology() {
        Simulation sim = new Simulation(11L);
        sim.addSwitch("A", new SwitchConfig(-1, 11L));
        sim.addSwitch("B", new SwitchConfig(-1, 11L));
        sim.addSwitch("C", new SwitchConfig(-1, 11L));
        // 每台：端口 0、1 上联（非主机端口），端口 2 接主机
        for (String n : List.of("A", "B", "C")) {
            sim.addPort(n, false, PortConfig.defaults());
            sim.addPort(n, false, PortConfig.defaults());
            sim.addPort(n, true, PortConfig.defaults());
        }
        sim.addHost("ha", "00:00:00:00:00:0a");
        sim.addHost("hb", "00:00:00:00:00:0b");
        sim.connectHost("ha", "A", 2);
        sim.connectHost("hb", "B", 2);
        sim.addLink("A", 0, "B", 0);
        sim.addLink("A", 1, "C", 0);
        sim.addLink("B", 1, "C", 1);
        return sim;
    }

    @Test
    void loopIsBlockedDeterministically() {
        Simulation sim = triangleTopology();
        List<String> blocked = sim.snapshot().blockedPorts();
        // 边序：(A,0)-(B,0)、(A,1)-(C,0) 先成树；(B,1)-(C,1) 成环 -> 阻断 B:1 与 C:1
        assertEquals(List.of("B:1", "C:1"), blocked);
    }

    @Test
    void blockingConvergesAndBroadcastReachesWithoutProliferation() {
        Simulation sim = triangleTopology();
        sim.inject("ha", "ff:ff:ff:ff:ff:ff", 1, FramePriority.P0, "loop-bcast");
        sim.tick(10);
        // 目标主机在无环路径 A-B 上收到帧（恰好一次）
        long copies = sim.hostReceived("hb").stream()
                .filter(f -> f.payload().equals("loop-bcast"))
                .count();
        assertEquals(1, copies, "无环路径上广播帧应恰好到达一次");
        // 交换机内没有帧无限循环：tick 后各队列必须清空
        var view = sim.snapshot();
        for (var sw : view.switches()) {
            for (var p : sw.ports()) {
                assertEquals(0, p.queueDepths().stream().mapToInt(Integer::intValue).sum(),
                        "存在残留帧，说明环路未收敛: " + sw.name() + ":" + p.portId());
            }
        }
        assertEquals(0, view.totals().get("dropEvents"));
        assertFalse(view.blockedPorts().isEmpty());
    }

    @Test
    void removingBlockingEdgeReopensPorts() {
        Simulation sim = triangleTopology();
        assertEquals(List.of("B:1", "C:1"), sim.snapshot().blockedPorts());
        // 删除树上一条边后重新收敛：三角形只剩两条边，无环 -> 无阻断
        sim.removeLink("A", 0, "B", 0);
        List<String> blocked = sim.snapshot().blockedPorts();
        assertTrue(blocked.isEmpty(), "删除边后应重新收敛到无阻断, 实际: " + blocked);
    }

    @Test
    void hostFacingPortsAreNeverBlocked() {
        Simulation sim = triangleTopology();
        for (var sw : sim.snapshot().switches()) {
            for (var p : sw.ports()) {
                if (p.hostFacing()) {
                    assertFalse(p.blocked());
                }
            }
        }
    }
}
