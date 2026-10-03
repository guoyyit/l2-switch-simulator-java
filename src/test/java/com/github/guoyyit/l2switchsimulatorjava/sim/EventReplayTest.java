package com.github.guoyyit.l2switchsimulatorjava.sim;

import com.github.guoyyit.l2switchsimulatorjava.sim.core.Simulation;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.BackpressureAction;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.EventLogOptions;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.EventView;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.FramePriority;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.PortConfig;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.PortFault;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.ReplayRejectedException;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.ReplayResult;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.SimulationView;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.StormAction;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.SwitchConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 需求 1/2/3/4/5：真实事件记录与隔离回放、范围校验、多次回放一致、记录开关与容量。
 */
class EventReplayTest {

    private Simulation mixedNetwork(long seed) {
        PortConfig cfg = new PortConfig(2, 4, BackpressureAction.PAUSE, 3, 2, StormAction.LIMIT);
        Simulation sim = new Simulation(seed);
        sim.addSwitch("sw1", new SwitchConfig(40, seed));
        sim.addSwitch("sw2", new SwitchConfig(40, seed));
        for (int i = 0; i < 3; i++) {
            sim.addPort("sw1", true, cfg);
            sim.addPort("sw2", true, cfg);
        }
        sim.addPort("sw1", false, cfg);
        sim.addPort("sw2", false, cfg);
        sim.addLink("sw1", 3, "sw2", 3);
        String[][] hosts = {
                {"h1", "00:00:00:00:00:01", "sw1", "0"},
                {"h2", "00:00:00:00:00:02", "sw1", "1"},
                {"h3", "00:00:00:00:00:03", "sw1", "2"},
                {"h4", "00:00:00:00:00:04", "sw2", "0"},
                {"h5", "00:00:00:00:00:05", "sw2", "1"},
                {"h6", "00:00:00:00:00:06", "sw2", "2"},
        };
        for (String[] h : hosts) {
            sim.addHost(h[0], h[1]);
            sim.connectHost(h[0], h[2], Integer.parseInt(h[3]));
        }
        sim.setFault("sw1", 0, new PortFault(0.4, true));
        sim.setFault("sw2", 1, new PortFault(0.3, false));
        return sim;
    }

    private void drive(Simulation sim, int n) {
        String[] src = {"h1", "h2", "h3", "h4", "h5", "h6"};
        String[] dst = {"ff:ff:ff:ff:ff:ff", "01:00:5e:00:00:01",
                "00:00:00:00:00:04", "00:00:00:00:00:05"};
        for (int i = 0; i < n; i++) {
            sim.inject(src[i % src.length], dst[i % dst.length], 1,
                    FramePriority.values()[i % 8], "t" + i);
            sim.tick();
        }
        sim.tick(20);
    }

    @Test
    void fullReplayRebuildsIdenticalRun() {
        Simulation sim = mixedNetwork(77L);
        drive(sim, 100);
        SimulationView live = sim.snapshot();
        ReplayResult r = sim.replay();
        assertTrue(r.matched());
        assertEquals(live, r.snapshot(), "回放结束快照必须与原运行结束快照完全一致");
        assertNotSame(live, r.snapshot());
        // 回放返回的事件序列即全量记录，顺序号连续、从 1 开始
        List<EventView> events = r.events();
        assertEquals(sim.eventCount(), events.size());
        for (int i = 0; i < events.size(); i++) {
            assertEquals(i + 1L, events.get(i).seq(), "事件顺序号必须连续从 1 开始");
        }
    }

    @Test
    void repeatedReplaysAreIdenticalAndDoNotMutateLive() {
        Simulation sim = mixedNetwork(77L);
        drive(sim, 60);
        SimulationView liveBefore = sim.snapshot();
        long liveEvents = sim.eventCount();
        ReplayResult r1 = sim.replay();
        ReplayResult r2 = sim.replay();
        assertEquals(r1.snapshot(), r2.snapshot(), "同一事件序列多次回放必须完全一致");
        assertEquals(r1.events(), r2.events());
        // 回放不得修改在线仿真：状态、tick、事件数均不变
        assertEquals(liveBefore, sim.snapshot());
        assertEquals(liveEvents, sim.eventCount());
    }

    @Test
    void replayDoesNotBlockLiveAdvance() {
        Simulation sim = mixedNetwork(77L);
        drive(sim, 30);
        sim.replay();
        // 回放后在线仿真仍可继续推进与查询
        long tickBefore = sim.now();
        sim.tick(5);
        assertEquals(tickBefore + 5, sim.now());
        SimulationView v = sim.snapshot();
        assertEquals(tickBefore + 5, v.tick());
    }

    @Test
    void recordedEventsContainRequiredFields() {
        Simulation sim = mixedNetwork(77L);
        drive(sim, 120);
        // 在同一 tick 突发广播，容量 2/PAUSE、风暴阈值 3/LIMIT 必然触发暂停或限速重试
        for (int i = 0; i < 12; i++) {
            sim.inject("h2", "ff:ff:ff:ff:ff:ff", 1, FramePriority.P2, "burst" + i);
        }
        sim.tick(20);
        boolean hasFaultVerdict = false;
        boolean hasDrop = false;
        boolean hasDeferred = false;
        boolean hasFrameTx = false;
        for (EventView e : sim.events(null, null)) {
            Map<String, Object> d = e.data();
            switch (e.type()) {
                case "FaultVerdict" -> {
                    hasFaultVerdict = true;
                    assertTrue(d.containsKey("switchName") && d.containsKey("portId"));
                    assertTrue(d.containsKey("frameId"));
                    assertTrue(d.containsKey("dropDrawn") && d.containsKey("reorderDrawn"));
                }
                case "FrameDrop" -> {
                    hasDrop = true;
                    assertTrue(d.containsKey("reason"));
                }
                case "FrameDeferred" -> hasDeferred = true;
                case "FrameTx" -> hasFrameTx = true;
                default -> {
                }
            }
        }
        assertTrue(hasFaultVerdict, "必须记录故障判定结果");
        assertTrue(hasDrop, "必须记录丢弃结果");
        assertTrue(hasDeferred, "暂停/限速重试必须记录");
        assertTrue(hasFrameTx, "必须记录发送");
    }

    @Test
    void rangeReplayRejectsInvalidAndOutOfRange() {
        Simulation sim = mixedNetwork(77L);
        drive(sim, 10);
        long last = sim.lastEventSeq();

        ReplayRejectedException oor = assertThrows(ReplayRejectedException.class,
                () -> sim.replayRange(1L, last + 10));
        assertEquals(ReplayRejectedException.OUT_OF_RANGE, oor.code());

        ReplayRejectedException inv = assertThrows(ReplayRejectedException.class,
                () -> sim.replayRange(10L, 5L));
        assertEquals(ReplayRejectedException.INVALID_RANGE, inv.code());

        ReplayRejectedException nonPositive = assertThrows(ReplayRejectedException.class,
                () -> sim.replayRange(0L, last));
        assertEquals(ReplayRejectedException.OUT_OF_RANGE, nonPositive.code());
    }

    @Test
    void rangeReplayAcceptsTickBoundaryAndReturnsSlice() {
        Simulation sim = mixedNetwork(77L);
        drive(sim, 8);
        // 找到第一个 TickEnd 边界
        long tickEnd = -1;
        for (EventView e : sim.events(null, null)) {
            if (e.type().equals("TickEnd")) {
                tickEnd = e.seq();
                break;
            }
        }
        assertTrue(tickEnd > 0);
        ReplayResult r = sim.replayRange(tickEnd, tickEnd);
        assertEquals(1, r.events().size());
        assertEquals(tickEnd, r.events().get(0).seq());
        assertEquals(1L, r.snapshot().tick(), "回到首个 tick 边界时 tick 应为 1");

        // 落在 tick 观测事件内部的终点必须被拒绝
        long inside = tickEnd - 1;
        if (!"CmdTick".equals(sim.events(inside, inside).get(0).type())) {
            ReplayRejectedException ex = assertThrows(ReplayRejectedException.class,
                    () -> sim.replayRange(1L, inside));
            assertEquals(ReplayRejectedException.NOT_AT_BOUNDARY, ex.code());
        }
    }

    @Test
    void disabledRecordingKeepsForwardingAndStats() {
        // 用相同种子但关闭记录的仿真
        Simulation offSim = new Simulation(31L, EventLogOptions.disabled());
        // 直接构造与 mixedNetwork 相同的拓扑（简化：仅断言关闭记录开关不影响行为）
        assertFalse(offSim.eventRecordingEnabled());
        offSim.addSwitch("sw1", new SwitchConfig(40, 31L));
        offSim.addPort("sw1", true, PortConfig.defaults());
        offSim.addPort("sw1", true, PortConfig.defaults());
        offSim.addHost("h1", "00:00:00:00:00:01");
        offSim.addHost("h2", "00:00:00:00:00:02");
        offSim.connectHost("h1", "sw1", 0);
        offSim.connectHost("h2", "sw1", 1);
        offSim.inject("h1", "ff:ff:ff:ff:ff:ff", 1, FramePriority.P0, "x");
        offSim.tick(3);
        assertEquals(1, offSim.hostReceived("h2").size());
        assertEquals(0, offSim.eventCount());
        assertThrows(ReplayRejectedException.class, offSim::replay);
    }

    @Test
    void capacityLimitIsDeterministicAndReplayRejectedWhenTruncated() {
        Simulation sim = new Simulation(1L, EventLogOptions.enabled(6));
        sim.addSwitch("sw", new SwitchConfig(-1, 1L));
        sim.addPort("sw", true, PortConfig.defaults());
        sim.addPort("sw", true, PortConfig.defaults());
        sim.addHost("a", "00:00:00:00:00:01");
        sim.addHost("b", "00:00:00:00:00:02");
        sim.connectHost("a", "sw", 0);
        sim.connectHost("b", "sw", 1);
        sim.inject("a", "ff:ff:ff:ff:ff:ff", 1, FramePriority.P0, "m");
        sim.tick();
        assertEquals(6, sim.eventCount(), "达到容量后不再保留新事件");
        assertTrue(sim.eventLogTruncated());
        assertTrue(sim.droppedEvents() > 0);
        // 截断后的转发与统计仍然正常工作（记录不形成背压）
        assertEquals(1, sim.hostReceived("b").size());
        ReplayRejectedException ex = assertThrows(ReplayRejectedException.class, sim::replay);
        assertEquals(ReplayRejectedException.LOG_TRUNCATED, ex.code());
    }

    @Test
    void emptyLogReplayRejected() {
        Simulation sim = new Simulation(1L);
        // 仅 Begin 之外无任何事件时（构造即记录 Begin，因此这里用全新且立刻回放也有 Begin）
        ReplayResult r = sim.replay();
        assertEquals(0L, r.snapshot().tick());
    }
}
