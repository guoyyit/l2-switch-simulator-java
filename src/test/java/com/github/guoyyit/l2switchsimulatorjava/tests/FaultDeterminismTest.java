package com.github.guoyyit.l2switchsimulatorjava.tests;

import com.github.guoyyit.l2switchsimulatorjava.core.Network;
import com.github.guoyyit.l2switchsimulatorjava.core.NetworkView;
import com.github.guoyyit.l2switchsimulatorjava.core.Replayer;
import com.github.guoyyit.l2switchsimulatorjava.core.SimulationEvent;
import com.github.guoyyit.l2switchsimulatorjava.core.SwitchConfig;
import com.github.guoyyit.l2switchsimulatorjava.model.Frame;
import com.github.guoyyit.l2switchsimulatorjava.model.MacAddress;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 需求 6：固定种子 + 相同事件序列下转发与丢弃逐次一致；故障注入不影响无关端口。
 */
class FaultDeterminismTest {

    private MacAddress mac(int v) {
        return new MacAddress(v);
    }

    private Network buildWithFaults(long seed, double dropP1, double oooP2) {
        Network net = new Network(seed);
        SwitchConfig cfg = new SwitchConfig();
        cfg.defaultQueueCapacity = 128;
        net.addSwitch("sw", 4, cfg);
        if (dropP1 > 0) {
            net.setFault("sw", 1, dropP1, 0.0);
        }
        if (oooP2 > 0) {
            net.setFault("sw", 2, 0.0, oooP2);
        }
        return net;
    }

    private void runScenario(Network net) {
        // 每帧不同未知目的 -> 全部泛洪到 1/2/3；端口 1 有丢帧故障，端口 2 有错序故障
        for (int i = 0; i < 40; i++) {
            net.inject("sw", 0,
                    new Frame(i, mac(0x100 + i), mac(0x200 + i), i % 2, 0, null));
        }
        net.drainHosts();
    }

    @Test
    void sameSeedSameOutcome() {
        Network n1 = buildWithFaults(12345L, 0.3, 0.5);
        Network n2 = buildWithFaults(12345L, 0.3, 0.5);
        runScenario(n1);
        runScenario(n2);

        NetworkView v1 = n1.snapshot();
        NetworkView v2 = n2.snapshot();
        // 统计计数器逐次一致
        for (int p = 0; p < 4; p++) {
            assertEquals(v1.switches().get(0).ports().get(p).statistics(),
                    v2.switches().get(0).ports().get(p).statistics(),
                    "端口 " + p + " 统计必须逐次一致");
            assertEquals(v1.switches().get(0).ports().get(p).queueSize(),
                    v2.switches().get(0).ports().get(p).queueSize(),
                    "端口 " + p + " 队列深度必须逐次一致");
        }
        assertEquals(v1.switches().get(0).fdb(), v2.switches().get(0).fdb(),
                "转发表必须逐次一致");
        assertEquals(n1.recentDrops(), n2.recentDrops(), "丢帧事件序列必须逐次一致");
        assertTrue(v1.switches().get(0).ports().get(1).statistics().faultDroppedFrames > 0,
                "端口 1 应发生故障丢帧");
        assertTrue(v1.switches().get(0).ports().get(2).statistics().outOfOrderEvents > 0,
                "端口 2 应发生错序");
    }

    @Test
    void differentSeedMayDifferButIsStable() {
        Network n1 = buildWithFaults(1L, 0.5, 0.0);
        Network n2 = buildWithFaults(1L, 0.5, 0.0);
        Network n3 = buildWithFaults(2L, 0.5, 0.0);
        runScenario(n1);
        runScenario(n2);
        runScenario(n3);
        assertEquals(n1.recentDrops(), n2.recentDrops());
        // 不同种子不必相同，但各自必须可复现（再次运行 2 号种子）
        Network n4 = buildWithFaults(2L, 0.5, 0.0);
        runScenario(n4);
        assertEquals(n3.recentDrops(), n4.recentDrops());
    }

    @Test
    void faultOnOnePortDoesNotAffectOthers() {
        Network net = new Network(7L);
        SwitchConfig cfg = new SwitchConfig();
        cfg.defaultQueueCapacity = 128;
        net.addSwitch("sw", 4, cfg);
        net.setFault("sw", 1, 1.0, 0.0); // 端口 1 必丢
        for (int i = 0; i < 10; i++) {
            net.inject("sw", 0, new Frame(i, mac(0x10 + i), mac(0x90 + i), 0, 0, null));
        }
        net.drainHosts(); // 出方向故障在交付时生效
        var ports = net.snapshot().switches().get(0).ports();
        assertEquals(0, ports.get(1).queueSize(), "故障端口的帧全部在出方向丢弃");
        assertEquals(0, ports.get(2).queueSize(), "无关端口 2 已正常交付（队列排空）");
        assertEquals(0, ports.get(3).queueSize(), "无关端口 3 已正常交付（队列排空）");
        assertEquals(10, ports.get(1).statistics().faultDroppedFrames, "端口 1 记录 10 次故障丢帧");
        assertEquals(0, ports.get(2).statistics().faultDroppedFrames);
        assertEquals(0, ports.get(3).statistics().faultDroppedFrames);
    }

    @Test
    void outOfOrderProducesReorderEvents() {
        Network net = new Network(7L);
        SwitchConfig cfg = new SwitchConfig();
        cfg.defaultQueueCapacity = 128;
        net.addSwitch("sw", 3, cfg);
        net.setFault("sw", 1, 0.0, 0.9);
        for (int i = 0; i < 20; i++) {
            net.inject("sw", 0, new Frame(i, mac(0x30 + i), mac(0x70 + i), 0, 0, null));
        }
        net.drainHosts();
        long reorders = net.snapshot().switches().get(0).ports().get(1).statistics().outOfOrderEvents;
        assertTrue(reorders > 0, "高错序概率下应观察到错序事件");
    }

    @Test
    void replayerReproducesSameSnapshot() {
        var topology = new Replayer.NetworkTopologyDef(
                List.of(new Replayer.SwitchDef("sw", 4)),
                List.of());
        List<SimulationEvent> events = new ArrayList<>();
        // 注意：回放网络不设置故障，验证纯转发顺序可复现
        for (int i = 0; i < 15; i++) {
            events.add(SimulationEvent.inject(0L, "sw", 0,
                    new Frame(i, mac(0x50 + i), mac(0x80 + i), i % 3, 0, null)));
        }
        events.add(SimulationEvent.drain(1L));
        Replayer replayer = new Replayer();
        NetworkView r1 = replayer.replay(99L, topology, events);
        NetworkView r2 = replayer.replay(99L, topology, events);
        // 比较可观察结果：FDB、端口统计与队列深度（对象地址不同，不做整对象深比）
        assertEquals(r1.switches().get(0).fdb(), r2.switches().get(0).fdb());
        for (int p = 0; p < 4; p++) {
            assertEquals(r1.switches().get(0).ports().get(p).statistics(),
                    r2.switches().get(0).ports().get(p).statistics());
            assertEquals(r1.switches().get(0).ports().get(p).queueSize(),
                    r2.switches().get(0).ports().get(p).queueSize());
        }
    }
}
