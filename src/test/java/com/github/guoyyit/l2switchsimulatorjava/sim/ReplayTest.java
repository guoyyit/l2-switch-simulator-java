package com.github.guoyyit.l2switchsimulatorjava.sim;

import com.github.guoyyit.l2switchsimulatorjava.sim.core.Simulation;
import com.github.guoyyit.l2switchsimulatorjava.sim.log.Event;
import com.github.guoyyit.l2switchsimulatorjava.sim.log.EventType;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.BackpressureAction;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.DropEvent;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.DropReason;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.Frame;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.FramePriority;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.PortConfig;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.PortView;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.SimulationView;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.StormAction;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.SwitchConfig;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.SwitchView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件记录与回放、重试计数一次性、统计守恒、回放隔离与并发查询的自动化测试。
 */
class ReplayTest {

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

    /** 混合故障注入、风暴限速、队列背压与环路阻断的复杂场景。 */
    private Simulation mixedScenario() {
        Simulation sim = new Simulation(77L);
        sim.addSwitch("sw1", new SwitchConfig(40, 77L));
        sim.addSwitch("sw2", new SwitchConfig(40, 77L));
        for (int i = 0; i < 3; i++) {
            sim.addPort("sw1", true, PortConfig.defaults());
            sim.addPort("sw2", true, PortConfig.defaults());
        }
        sim.addPort("sw1", false, PortConfig.defaults());
        sim.addPort("sw2", false, PortConfig.defaults());
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
        // 混合：sw1:0 随机丢帧+错序；sw1:1 风暴限速；sw2:1 队列背压暂停
        sim.setFault("sw1", 0, new com.github.guoyyit.l2switchsimulatorjava.sim.model.PortFault(0.3, true));
        sim.configurePort("sw1", 1, new PortConfig(64, 1000, BackpressureAction.DROP, 4, 8, StormAction.LIMIT));
        sim.configurePort("sw2", 1, new PortConfig(2, 1000, BackpressureAction.PAUSE, 1000, 8, StormAction.DROP));
        String[] src = {"h1", "h2", "h3", "h4", "h5", "h6"};
        String[] dst = {"ff:ff:ff:ff:ff:ff", "01:00:5e:00:00:01",
                "00:00:00:00:00:04", "00:00:00:00:00:05", "00:00:00:00:00:06"};
        for (int i = 0; i < 90; i++) {
            sim.inject(src[i % src.length], dst[i % dst.length], 1,
                    FramePriority.values()[i % 8], "m" + i);
            sim.tick();
        }
        sim.tick(20);
        return sim;
    }

    private static long countEventsOfType(List<Event> events, EventType type) {
        return events.stream().filter(e -> e.type() == type).count();
    }

    @Test
    void replayReconstructsExactEndStateAndDropEvents() {
        Simulation sim = mixedScenario();
        SimulationView live = sim.snapshot();
        List<DropEvent> liveDrops = sim.dropEvents();

        Simulation.ReplayResult r = sim.replay(0, sim.eventLogStatus().get("latestSeq") == null
                ? -1L : ((Number) sim.eventLogStatus().get("latestSeq")).longValue());
        // 回放结束时刻状态与原始运行完全一致
        assertEquals(live, r.state(), "回放结束时刻状态必须与原始运行一致");
        // 丢弃事件明细逐次一致
        assertEquals(liveDrops, r.dropEvents(), "回放丢弃事件明细必须与原始运行一致");
        // 转发顺序（主机已收帧列表）逐次一致
        for (SwitchView sw : r.state().switches()) {
            for (PortView p : sw.ports()) {
                assertEquals(live.switches().stream().filter(x -> x.name().equals(sw.name())).findFirst().orElseThrow()
                        .ports().get(p.portId()).counters(), p.counters());
            }
        }
    }

    @Test
    void replayIsDeterministicAcrossMultipleReplays() {
        Simulation sim = mixedScenario();
        long latest = ((Number) sim.eventLogStatus().get("latestSeq")).longValue();
        Simulation.ReplayResult r1 = sim.replay(0, latest);
        Simulation.ReplayResult r2 = sim.replay(0, latest);
        assertEquals(r1.state(), r2.state(), "同一事件序列多次回放结果必须完全一致");
        assertEquals(r1.dropEvents(), r2.dropEvents());
    }

    @Test
    void replayDoesNotAffectLiveSimulation() {
        Simulation sim = mixedScenario();
        SimulationView before = sim.snapshot();
        long latest = ((Number) sim.eventLogStatus().get("latestSeq")).longValue();
        sim.replay(0, latest);
        // 回放不修改在线仿真状态
        assertEquals(before, sim.snapshot(), "回放不得修改在线仿真状态");
        // 回放后在线仿真仍可继续推进与查询
        sim.inject("h1", "00:00:00:00:00:02", 1, FramePriority.P0, "after-replay");
        sim.tick(5);
        SimulationView after = sim.snapshot();
        assertNotEquals(before.tick(), after.tick());
        assertTrue(after.tick() > before.tick());
    }

    @Test
    void replayRangeValidationRejectsWithDistinguishableReasons() {
        Simulation sim = twoHosts(1L);
        for (int i = 0; i < 5; i++) {
            sim.inject("h1", "ff:ff:ff:ff:ff:ff", 1, FramePriority.P0, "b" + i);
            sim.tick();
        }
        long latest = ((Number) sim.eventLogStatus().get("latestSeq")).longValue();

        // 超出已记录范围
        IllegalArgumentException beyond = assertThrows(IllegalArgumentException.class,
                () -> sim.replay(0, latest + 10));
        assertTrue(beyond.getMessage().contains("超出已记录范围"), "原因: " + beyond.getMessage());

        // 范围非法：from < 0
        IllegalArgumentException illegalFrom = assertThrows(IllegalArgumentException.class,
                () -> sim.replay(-1, latest));
        assertTrue(illegalFrom.getMessage().contains("范围非法"), "原因: " + illegalFrom.getMessage());

        // 范围非法：to < from
        IllegalArgumentException illegalOrder = assertThrows(IllegalArgumentException.class,
                () -> sim.replay(10, 5));
        assertTrue(illegalOrder.getMessage().contains("范围非法"), "原因: " + illegalOrder.getMessage());

        // 空日志无法回放（裸 Simulation，未做任何建模）
        Simulation empty = new Simulation(2L);
        IllegalStateException emptyErr = assertThrows(IllegalStateException.class,
                () -> empty.replay(0, 0));
        assertTrue(emptyErr.getMessage().contains("为空"), "原因: " + emptyErr.getMessage());
    }

    @Test
    void replayRejectsWhenLogTruncated() {
        Simulation sim = twoHosts(1L);
        // 极小容量，制造淘汰
        sim.configureEventLog(true, 5);
        for (int i = 0; i < 20; i++) {
            sim.inject("h1", "ff:ff:ff:ff:ff:ff", 1, FramePriority.P0, "t" + i);
            sim.tick();
        }
        long oldest = ((Number) sim.eventLogStatus().get("oldestSeq")).longValue();
        long latest = ((Number) sim.eventLogStatus().get("latestSeq")).longValue();
        assertTrue(oldest > 0, "日志应已淘汰最旧事件");
        // 淘汰后无法完整回放
        IllegalStateException truncated = assertThrows(IllegalStateException.class,
                () -> sim.replay(oldest, latest));
        assertTrue(truncated.getMessage().contains("已被淘汰"), "原因: " + truncated.getMessage());
        // 事件查询仍可读保留窗口
        List<Event> events = sim.events(oldest, latest);
        assertTrue(events.get(0).seq() >= oldest);
    }

    @Test
    void pauseRetryCountsReceiveOnceAndDecisionOnce() {
        Simulation sim = new Simulation(9L);
        sim.addSwitch("sw", new SwitchConfig(-1, 9L));
        // 建端口时即小容量（configurePort 不缩小队列），确保输出队列满后触发 PAUSE
        PortConfig cfg = new PortConfig(2, 1000, BackpressureAction.PAUSE, 1000, 8, StormAction.DROP);
        sim.addPort("sw", true, cfg);
        sim.addPort("sw", true, cfg);
        sim.addHost("h1", "00:00:00:00:00:01");
        sim.addHost("h2", "00:00:00:00:00:02");
        sim.connectHost("h1", "sw", 0);
        sim.connectHost("h2", "sw", 1);
        // 先学习 h2
        sim.inject("h2", "00:00:00:00:00:01", 1, FramePriority.P0, "learn");
        sim.tick(2);
        sim.setFault("sw", 0, new com.github.guoyyit.l2switchsimulatorjava.sim.model.PortFault(0.3, false));
        int n = 10;
        for (int i = 0; i < n; i++) {
            sim.inject("h1", "00:00:00:00:00:02", 1, FramePriority.P0, "p" + i);
        }
        sim.tick(40);

        List<Event> events = sim.events(0, ((Number) sim.eventLogStatus().get("latestSeq")).longValue());
        long faultDecisions = countEventsOfType(events, EventType.FAULT_DECISION);
        long faultDropped = sim.dropCount(DropReason.FAULT_DROP);
        long rx = sim.snapshot().switches().get(0).ports().get(0).counters().rxFrames();
        long delivered = sim.hostReceived("h2").stream().filter(f -> f.payload().startsWith("p")).count();

        // 每帧只判定一次故障（重试不重新抛硬币）
        assertEquals(n, faultDecisions, "故障判定次数必须等于帧数（每帧只判一次）");
        // 接收计数只计一次（重试不重复）
        assertEquals(n - faultDropped, rx, "接收计数必须只计真实到达的帧一次");
        // 送达数 == 接收计数
        assertEquals(rx, delivered, "送达数必须等于接收计数");
        // 确实发生过暂停重试
        assertTrue(sim.snapshot().switches().get(0).ports().get(0).counters().pausedTicks() > 0);
    }

    @Test
    void stormLimitRetryCountsReceiveOnceAndDecisionOnce() {
        Simulation sim = twoHosts(11L);
        // 风暴限速：阈值 2/窗口 4，超出的帧留在队首重试
        PortConfig cfg = new PortConfig(64, 1000, BackpressureAction.DROP, 2, 4, StormAction.LIMIT);
        sim.configurePort("sw", 0, cfg);
        sim.configurePort("sw", 1, cfg);
        sim.setFault("sw", 0, new com.github.guoyyit.l2switchsimulatorjava.sim.model.PortFault(0.2, false));
        int n = 8;
        for (int i = 0; i < n; i++) {
            sim.inject("h1", "ff:ff:ff:ff:ff:ff", 1, FramePriority.P0, "s" + i);
        }
        sim.tick(40);

        List<Event> events = sim.events(0, ((Number) sim.eventLogStatus().get("latestSeq")).longValue());
        long faultDecisions = countEventsOfType(events, EventType.FAULT_DECISION);
        long faultDropped = sim.dropCount(DropReason.FAULT_DROP);
        long rx = sim.snapshot().switches().get(0).ports().get(0).counters().rxFrames();
        long delivered = sim.hostReceived("h2").stream().filter(f -> f.payload().startsWith("s")).count();

        assertEquals(n, faultDecisions, "限速重试下故障判定仍只进行一次");
        assertEquals(n - faultDropped, rx, "限速重试下接收计数只计一次");
        assertEquals(rx, delivered, "限速重试下送达数等于接收计数");
        // 限速不产生风暴丢弃
        assertEquals(0, sim.dropCount(DropReason.STORM_CONTROL));
    }

    @Test
    void mixedFaultStatsConservation() {
        Simulation sim = mixedScenario();
        assertConservation(sim);
    }

    @Test
    void concurrentReplayAndLiveQueriesAreConsistent() throws Exception {
        Simulation sim = mixedScenario();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        // 3 个回放线程：各自独立回放，结果必须与在线一致且互不干扰
        for (int r = 0; r < 3; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    long latest = ((Number) sim.eventLogStatus().get("latestSeq")).longValue();
                    Simulation.ReplayResult a = sim.replay(0, latest);
                    Simulation.ReplayResult b = sim.replay(0, latest);
                    assertEquals(a.state(), b.state());
                    assertEquals(a.dropEvents(), b.dropEvents());
                    assertConservationState(a.state());
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
        }
        // 3 个在线查询线程：持续抓快照并做守恒校验
        for (int r = 0; r < 3; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (finished.getCount() > 0) {
                        assertConservationState(sim.snapshot());
                        Thread.sleep(1L);
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
        }
        start.countDown();
        // 在线仿真继续推进
        for (int i = 0; i < 60; i++) {
            sim.inject("h1", "ff:ff:ff:ff:ff:ff", 1, FramePriority.P0, "c" + i);
            sim.tick();
        }
        finished.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS), "线程应在时限内结束");
        assertEquals(0, errors.size(), () -> "并发期间出现错误: " + errors);
        assertConservation(sim);
    }

    // ---- 守恒校验 ----

    private static void assertConservation(Simulation sim) {
        SimulationView v = sim.snapshot();
        assertConservationState(v);
        // 丢弃事件明细 == 按原因汇总 == 端口 dropped 之和
        long fromEvents = sim.dropEvents().size();
        long byReason = v.dropReasons().values().stream().mapToLong(Long::longValue).sum();
        long portDropped = 0;
        for (SwitchView sw : v.switches()) {
            for (PortView p : sw.ports()) {
                portDropped += p.counters().dropped();
            }
        }
        assertEquals(fromEvents, byReason, "丢弃事件数必须等于按原因汇总");
        assertEquals(fromEvents, portDropped, "丢弃事件数必须等于端口 dropped 之和");
        // 主机已收帧列表 == 全局 hostDelivered
        long hostReceived = 0;
        for (var h : v.hosts()) {
            hostReceived += h.rxFrames().size();
        }
        assertEquals(hostReceived, v.totals().get("hostDelivered"), "主机已收帧列表必须等于 hostDelivered");
    }

    private static void assertConservationState(SimulationView v) {
        // 全局帧守恒：注入 + 泛洪净增副本 == 已送达 + 丢弃 + 队列总量
        long totalQueue = 0;
        long portDropped = 0;
        for (SwitchView sw : v.switches()) {
            for (PortView p : sw.ports()) {
                totalQueue += p.ingressDepth();
                for (Integer d : p.queueDepths()) {
                    totalQueue += d;
                }
                portDropped += p.counters().dropped();
            }
        }
        long injected = v.totals().get("injected");
        long floodNet = v.totals().get("floodNetCopies");
        long filtered = v.totals().getOrDefault("filtered", 0L);
        long delivered = v.totals().get("hostDelivered");
        long drops = v.totals().get("dropEvents");
        assertEquals(injected + floodNet, delivered + drops + totalQueue + filtered,
                "帧守恒: 注入+泛洪净副本 == 送达+丢弃+队列+过滤, injected=" + injected + " floodNet=" + floodNet
                        + " filtered=" + filtered + " delivered=" + delivered + " drops=" + drops
                        + " queue=" + totalQueue);
        // 按原因丢弃之和 == 端口 dropped 之和
        long byReason = v.dropReasons().values().stream().mapToLong(Long::longValue).sum();
        assertEquals(byReason, portDropped, "按原因丢弃之和必须等于端口 dropped 之和");
        // 主机接入端口的 txFrames == 该主机已收帧数
        for (SwitchView sw : v.switches()) {
            for (PortView p : sw.ports()) {
                if (p.hostFacing() && p.attachedHost() != null) {
                    long hostRx = v.hosts().stream().filter(h -> h.name().equals(p.attachedHost()))
                            .findFirst().orElseThrow().rxFrames().size();
                    assertEquals(hostRx, p.counters().txFrames(),
                            "主机端口 txFrames 必须等于主机已收帧数: " + sw.name() + ":" + p.portId());
                }
            }
        }
    }
}
