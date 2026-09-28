package com.github.guoyyit.l2switchsimulatorjava.sim;

import com.github.guoyyit.l2switchsimulatorjava.sim.core.Simulation;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.FdbEntryView;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.Frame;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.FramePriority;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.PortConfig;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.SimulationView;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.SwitchConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 需求 2：源地址学习、重复学习刷新、老化过期、端口禁用与转发域变化失效。 */
class LearningAgingTest {

    private Simulation basicTopology(long agingTicks) {
        Simulation sim = new Simulation(7L);
        sim.addSwitch("sw", new SwitchConfig(agingTicks, 7L));
        sim.addPort("sw", true, PortConfig.defaults());
        sim.addPort("sw", true, PortConfig.defaults());
        sim.addPort("sw", true, PortConfig.defaults());
        sim.addHost("h1", "00:00:00:00:00:01");
        sim.addHost("h2", "00:00:00:00:00:02");
        sim.addHost("h3", "00:00:00:00:00:03");
        sim.connectHost("h1", "sw", 0);
        sim.connectHost("h2", "sw", 1);
        sim.connectHost("h3", "sw", 2);
        return sim;
    }

    private List<FdbEntryView> fdb(Simulation sim) {
        return sim.snapshot().switches().get(0).fdb();
    }

    @Test
    void learnsSourceMacOnIngress() {
        Simulation sim = basicTopology(100);
        // h1 -> h2：h1 的源 MAC 应被学习到端口 0；h2 未知，帧被泛洪
        sim.inject("h1", "00:00:00:00:00:02", 1, FramePriority.P0, "first");
        sim.tick();

        List<FdbEntryView> table = fdb(sim);
        assertEquals(1, table.size());
        assertEquals("00:00:00:00:00:01", table.get(0).mac().toString());
        assertEquals(0, table.get(0).portId());
        assertEquals(1, table.get(0).vlan());
        assertEquals(1, table.get(0).learnedAt());
        assertEquals(101, table.get(0).expiresAt());

        // 第二台主机发送后，两条学习都应存在
        sim.inject("h2", "00:00:00:00:00:01", 1, FramePriority.P0, "reply");
        sim.tick();
        assertEquals(2, fdb(sim).size());
    }

    @Test
    void knownUnicastGoesOnlyToLearnedPort() {
        Simulation sim = basicTopology(100);
        // 先让双方互学
        sim.inject("h1", "00:00:00:00:00:02", 1, FramePriority.P0, "a");
        sim.tick(2);
        sim.inject("h2", "00:00:00:00:00:01", 1, FramePriority.P0, "b");
        sim.tick(2);
        // 再发已知单播：只有 h2 收到；h3 不应收到
        sim.inject("h1", "00:00:00:00:00:02", 1, FramePriority.P0, "known");
        sim.tick(2);
        List<Frame> h2rx = sim.hostReceived("h2");
        assertTrue(h2rx.stream().anyMatch(f -> f.payload().equals("known")));
        assertTrue(sim.hostReceived("h3").stream().noneMatch(f -> f.payload().equals("known")));
    }

    @Test
    void repeatLearningRefreshesAging() {
        Simulation sim = basicTopology(10);
        sim.inject("h1", "00:00:00:00:00:02", 1, FramePriority.P0, "a");
        sim.tick(3);
        FdbEntryView first = fdb(sim).get(0);
        assertEquals(1, first.learnedAt());

        // 在过期前再次从 h1 发送，计时被刷新
        sim.inject("h1", "00:00:00:00:00:02", 1, FramePriority.P0, "refresh");
        sim.tick();
        FdbEntryView refreshed = fdb(sim).get(0);
        assertEquals(4, refreshed.learnedAt());
        assertEquals(14, refreshed.expiresAt());

        // 等到原始条目本应过期之后，刷新后的条目仍在
        sim.tick(8);
        assertEquals(1, fdb(sim).size());

        // 最终过期
        sim.tick(3);
        assertTrue(fdb(sim).isEmpty());
    }

    @Test
    void entriesExpireAfterAgingTime() {
        Simulation sim = basicTopology(5);
        sim.inject("h1", "00:00:00:00:00:02", 1, FramePriority.P0, "a");
        sim.tick();
        assertEquals(1, fdb(sim).size());
        sim.tick(5); // learnedAt=1, expiresAt=6；tick=6 时过期
        assertTrue(fdb(sim).isEmpty());
    }

    @Test
    void disabledPortInvalidatesLearnedEntries() {
        Simulation sim = basicTopology(-1);
        sim.inject("h1", "00:00:00:00:00:02", 1, FramePriority.P0, "a");
        sim.tick();
        assertEquals(1, fdb(sim).size());
        sim.setPortEnabled("sw", 0, false);
        assertFalse(fdb(sim).stream().anyMatch(e -> e.portId() == 0));
    }

    @Test
    void vlanChangeInvalidatesLearnedEntries() {
        Simulation sim = basicTopology(-1);
        sim.inject("h1", "00:00:00:00:00:02", 1, FramePriority.P0, "a");
        sim.tick();
        assertEquals(1, fdb(sim).size());
        sim.setVlan("sw", 0, 2);
        assertTrue(fdb(sim).isEmpty());
    }

    @Test
    void learningIsKeyedByVlan() {
        Simulation sim = basicTopology(-1);
        sim.inject("h1", "00:00:00:00:00:02", 1, FramePriority.P0, "v1");
        sim.tick();
        sim.inject("h1", "00:00:00:00:00:02", 2, FramePriority.P0, "v2");
        sim.tick();
        assertEquals(2, fdb(sim).size());
    }

    @Test
    void noAgingWhenConfiguredMinusOne() {
        Simulation sim = basicTopology(-1);
        sim.inject("h1", "00:00:00:00:00:02", 1, FramePriority.P0, "a");
        sim.tick(1000);
        assertEquals(1, fdb(sim).size());
        assertEquals(Long.MAX_VALUE, fdb(sim).get(0).expiresAt());
    }

    @Test
    void sameSeedAndEventsProduceSameFdb() {
        SimulationView v1 = replay();
        SimulationView v2 = replay();
        assertEquals(v1, v2);
    }

    private SimulationView replay() {
        Simulation sim = basicTopology(50);
        for (int i = 0; i < 10; i++) {
            sim.inject((i % 3 == 0) ? "h1" : (i % 3 == 1) ? "h2" : "h3",
                    "ff:ff:ff:ff:ff:ff", 1, FramePriority.values()[i % 8], "m" + i);
            sim.tick();
        }
        sim.tick(3);
        return sim.snapshot();
    }
}
