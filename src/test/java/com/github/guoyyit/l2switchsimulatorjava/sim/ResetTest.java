package com.github.guoyyit.l2switchsimulatorjava.sim;

import com.github.guoyyit.l2switchsimulatorjava.sim.core.Simulation;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.FramePriority;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.PortConfig;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.SwitchConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 仿真重置必须清空交换机、主机、链路阻断、队列与全部统计。 */
class ResetTest {

    private Simulation populated() {
        Simulation sim = new Simulation(5L);
        sim.addSwitch("A", new SwitchConfig(-1, 5L));
        sim.addSwitch("B", new SwitchConfig(-1, 5L));
        sim.addPort("A", false, PortConfig.defaults());
        sim.addPort("A", true, PortConfig.defaults());
        sim.addPort("B", false, PortConfig.defaults());
        sim.addPort("B", true, PortConfig.defaults());
        sim.addLink("A", 0, "B", 0);
        sim.addHost("h1", "00:00:00:00:00:01");
        sim.addHost("h2", "00:00:00:00:00:02");
        sim.connectHost("h1", "A", 1);
        sim.connectHost("h2", "B", 1);
        sim.inject("h1", "ff:ff:ff:ff:ff:ff", 1, FramePriority.P0, "x");
        sim.tick(3);
        return sim;
    }

    @Test
    void resetClearsEverything() {
        Simulation sim = populated();
        assertTrue(sim.now() > 0);
        assertEquals(2, sim.snapshot().switches().size());
        assertEquals(1, sim.snapshot().links().size());
        assertTrue(sim.snapshot().totals().get("injected") > 0);

        sim.reset();
        var v = sim.snapshot();
        assertEquals(0, v.tick());
        assertTrue(v.switches().isEmpty());
        assertTrue(v.hosts().isEmpty());
        assertTrue(v.links().isEmpty());
        assertTrue(v.blockedPorts().isEmpty());
        assertEquals(0L, v.totals().get("injected"));
        assertEquals(0L, v.totals().get("hostDelivered"));
        assertTrue(sim.dropEvents().isEmpty());
        v.dropReasons().values().forEach(c -> assertEquals(0L, c));
    }

    @Test
    void topologyReusableAfterReset() {
        Simulation sim = populated();
        sim.reset();
        // 重置后使用原已占用的端口号重新建链，不应报“端口已存在链路”
        sim.addSwitch("A", new SwitchConfig(-1, 5L));
        sim.addSwitch("B", new SwitchConfig(-1, 5L));
        sim.addPort("A", false, PortConfig.defaults());
        sim.addPort("B", false, PortConfig.defaults());
        sim.addLink("A", 0, "B", 0);
        assertEquals(1, sim.snapshot().links().size());
    }
}
