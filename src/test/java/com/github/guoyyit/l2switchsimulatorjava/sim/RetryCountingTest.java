package com.github.guoyyit.l2switchsimulatorjava.sim;

import com.github.guoyyit.l2switchsimulatorjava.sim.core.Simulation;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.BackpressureAction;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.DropReason;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.EventView;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.FramePriority;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.PortConfig;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.PortCountersView;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.PortFault;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.PortView;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.StormAction;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.SwitchConfig;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 需求 6/7：暂停与限速重试期间同一帧只计一次接收、故障判定在重试前后不变、
 * 同一帧最终命运只决策一次，发送与丢弃也只计一次。
 */
class RetryCountingTest {

    private Simulation twoHosts(PortConfig cfg, long seed) {
        Simulation sim = new Simulation(seed);
        sim.addSwitch("sw", new SwitchConfig(-1, seed));
        sim.addPort("sw", true, cfg);
        sim.addPort("sw", true, cfg);
        sim.addHost("h1", "00:00:00:00:00:01");
        sim.addHost("h2", "00:00:00:00:00:02");
        sim.connectHost("h1", "sw", 0);
        sim.connectHost("h2", "sw", 1);
        return sim;
    }

    private PortCountersView inPort(Simulation sim) {
        return sim.snapshot().switches().get(0).ports().get(0).counters();
    }

    /** 统计每个帧标识在事件流中的出现类别计数。 */
    private Map<Long, Map<String, Long>> perFrameEventCounts(Simulation sim) {
        Map<Long, Map<String, Long>> m = new HashMap<>();
        for (EventView e : sim.events(null, null)) {
            Object fid = e.data().get("frameId");
            if (fid == null) {
                continue;
            }
            long id = ((Number) fid).longValue();
            m.computeIfAbsent(id, k -> new HashMap<>())
                    .merge(e.type(), 1L, Long::sum);
        }
        return m;
    }

    @Test
    void pauseRetriesCountArrivalAndFaultVerdictOnlyOnce() {
        // 容量极小 + PAUSE：突发单播迫使队首帧多次重试
        PortConfig cfg = new PortConfig(1, 4, BackpressureAction.PAUSE, 1_000_000, 8,
                StormAction.DROP);
        Simulation sim = twoHosts(cfg, 11L);
        // 先让 dst 已知
        sim.inject("h2", "00:00:00:00:00:01", 1, FramePriority.P0, "learn");
        sim.tick(2);
        sim.setFault("sw", 0, new PortFault(0.0, false));
        for (int i = 0; i < 8; i++) {
            sim.inject("h1", "00:00:00:00:00:02", 1, FramePriority.P0, "p" + i);
        }
        sim.tick(30);

        // 8 帧全部送达（PAUSE 不丢帧）
        assertEquals(8, sim.hostReceived("h2").stream()
                .filter(f -> f.payload().startsWith("p")).count());
        // 入端口到达只计 8 次（重试不重复计入接收）
        PortCountersView c = inPort(sim);
        assertEquals(8, c.ingressArrivals(), "暂停重试不得重复计入接收");
        assertEquals(8, c.rxFrames());
        assertEquals(0, c.dropped());

        // 每个帧：恰好一次到达、至多一次暂停，绝不重复判定
        Map<Long, Map<String, Long>> perFrame = perFrameEventCounts(sim);
        long p0 = perFrame.values().stream()
                .filter(m -> m.getOrDefault("FrameAdmitted", 0L) != 1L).count();
        assertEquals(0, p0, "每个帧只应到达一次");
        perFrame.values().forEach(m -> {
            long verdicts = m.getOrDefault("FaultVerdict", 0L);
            assertTrue(verdicts <= 1L, "同一帧故障判定只能发生一次，实际 " + verdicts);
        });
    }

    @Test
    void stormLimitRetriesDoNotRecountOrRedrop() {
        PortConfig cfg = new PortConfig(64, 4, BackpressureAction.DROP, 4, 2, StormAction.LIMIT);
        Simulation sim = twoHosts(cfg, 22L);
        for (int i = 0; i < 10; i++) {
            sim.inject("h1", "ff:ff:ff:ff:ff:ff", 1, FramePriority.P0, "l" + i);
        }
        sim.tick(80);
        // LIMIT 不丢帧，全部送达
        assertEquals(0, sim.dropCount(DropReason.STORM_CONTROL));
        assertEquals(10, sim.hostReceived("h2").stream().filter(f -> f.payload().startsWith("l")).count());
        PortCountersView c = inPort(sim);
        assertEquals(10, c.ingressArrivals(), "限速重试不得重复计入接收");
        // 至少发生过一次限速延迟
        long deferred = sim.events(null, null).stream()
                .filter(e -> e.type().equals("FrameDeferred")).count();
        assertTrue(deferred > 0, "应至少发生一次风暴限速延迟");
        // 每帧到达、判定都只有一次
        perFrameEventCounts(sim).values().forEach(m -> {
            assertEquals(1L, m.getOrDefault("FrameAdmitted", 0L), "每帧到达一次");
            assertTrue(m.getOrDefault("FaultVerdict", 0L) <= 1L, "每帧故障判定至多一次");
        });
    }

    @Test
    void faultDecisionFrozenAcrossRetriesCannotChangeToDrop() {
        // 每优先级输出容量 1 触发 PAUSE；入端口暂存容量为 1*8=8，注入 8 帧全部入队
        PortConfig cfg = new PortConfig(1, 4, BackpressureAction.PAUSE, 1_000_000, 8,
                StormAction.DROP);
        Simulation sim = twoHosts(cfg, 33L);
        sim.inject("h2", "00:00:00:00:00:01", 1, FramePriority.P0, "learn");
        sim.tick(2);
        sim.setFault("sw", 0, new PortFault(0.5, false));
        for (int i = 0; i < 8; i++) {
            sim.inject("h1", "ff:ff:ff:ff:ff:ff", 1, FramePriority.P0, "f" + i);
        }
        sim.tick(80);

        long faultDrops = sim.dropCount(DropReason.FAULT_DROP);
        long delivered = sim.hostReceived("h2").stream()
                .filter(f -> f.payload().startsWith("f")).count();
        // 命运守恒：故障丢弃 + 送达 == 注入 10，重试不会把不丢改成丢
        assertEquals(8, faultDrops + delivered);
        assertEquals(0, inPort(sim).admissionRejected(), "所有帧都应真实入队，不发生入端拒绝");

        // 每个帧的故障判定结果与最终命运一致：
        // dropDrawn=true 的帧必须恰好对应一次 FAULT_DROP，且每帧只有一个 FaultVerdict
        Map<Long, Boolean> verdict = new HashMap<>();
        Map<Long, Long> dropsPerFrame = new HashMap<>();
        for (EventView e : sim.events(null, null)) {
            Object fid = e.data().get("frameId");
            if (fid == null) {
                continue;
            }
            long id = ((Number) fid).longValue();
            if (e.type().equals("FaultVerdict")) {
                assertFalse(verdict.containsKey(id), "同一帧出现多次故障判定（重试重抽）: " + id);
                verdict.put(id, (Boolean) e.data().get("dropDrawn"));
            }
            if (e.type().equals("FrameDrop")) {
                dropsPerFrame.merge(id, 1L, Long::sum);
            }
        }
        dropsPerFrame.values().forEach(n -> assertEquals(1L, n, "同一帧丢弃只能计一次"));
        for (Map.Entry<Long, Boolean> en : verdict.entrySet()) {
            boolean wasDropped = dropsPerFrame.getOrDefault(en.getKey(), 0L) == 1L;
            assertEquals(en.getValue(), wasDropped,
                    "故障判定结果必须与最终命运一致（重试不得改判）: frame=" + en.getKey());
        }
    }

    @Test
    void transmitAndDropCountedOnceUnderBackpressureAndFaults() {
        PortConfig cfg = new PortConfig(1, 4, BackpressureAction.PAUSE, 1_000_000, 8,
                StormAction.DROP);
        Simulation sim = twoHosts(cfg, 44L);
        sim.setFault("sw", 0, new PortFault(0.5, false));
        for (int i = 0; i < 8; i++) {
            sim.inject("h1", "ff:ff:ff:ff:ff:ff", 1, FramePriority.P0, "x" + i);
        }
        sim.tick(80);
        // 发送计数 == 主机真实收到（主机侧端口）；不存在重复 tx
        long txToHost = sim.snapshot().switches().get(0).ports().get(1).counters().txFrames();
        assertEquals(sim.hostReceived("h2").size(), txToHost);
        // 每个发送帧只有一个 FrameTx/FrameDelivered
        Map<Long, Long> txPerFrame = new HashMap<>();
        Map<Long, Long> delPerFrame = new HashMap<>();
        for (EventView e : sim.events(null, null)) {
            Object fid = e.data().get("frameId");
            if (fid == null) {
                continue;
            }
            long id = ((Number) fid).longValue();
            if (e.type().equals("FrameTx")) {
                txPerFrame.merge(id, 1L, Long::sum);
            }
            if (e.type().equals("FrameDelivered")) {
                delPerFrame.merge(id, 1L, Long::sum);
            }
        }
        txPerFrame.values().forEach(n -> assertEquals(1L, n, "同一帧发送只能计一次"));
        delPerFrame.values().forEach(n -> assertEquals(1L, n, "同一帧主机收只能计一次"));
    }
}
