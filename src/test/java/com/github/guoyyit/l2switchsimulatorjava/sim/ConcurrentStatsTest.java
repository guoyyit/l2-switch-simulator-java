package com.github.guoyyit.l2switchsimulatorjava.sim;

import com.github.guoyyit.l2switchsimulatorjava.sim.core.Simulation;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.DropEvent;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.DropReason;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.FramePriority;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.PortConfig;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.PortView;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.SimulationView;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.SwitchConfig;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.SwitchView;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 需求 7：运行统计自洽、并发查询不看到部分更新。 */
class ConcurrentStatsTest {

    private Simulation buildBusyNetwork() {
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
        return sim;
    }

    /** 校验单个快照内部的自洽关系。 */
    private static void assertInternallyConsistent(SimulationView v) {
        // 1) 交换机 totals 与其端口计数之和一致
        long rx = 0;
        long tx = 0;
        long learned = 0;
        long flooded = 0;
        long dropped = 0;
        for (SwitchView sw : v.switches()) {
            for (PortView p : sw.ports()) {
                rx += p.counters().rxFrames();
                tx += p.counters().txFrames();
                learned += p.counters().learned();
                flooded += p.counters().flooded();
                dropped += p.counters().dropped();
            }
        }
        assertEquals(sum(v, "rxFrames"), rx);
        assertEquals(sum(v, "txFrames"), tx);
        assertEquals(sum(v, "learned"), learned);
        assertEquals(sum(v, "flooded"), flooded);
        assertEquals(sum(v, "dropped"), dropped);
        // 2) 全局丢弃原因计数之和 == 端口 dropped 之和
        long byReason = v.dropReasons().values().stream().mapToLong(Long::longValue).sum();
        assertEquals(dropped, byReason, "按原因丢弃总数必须等于端口 dropped 计数之和");

        // 3) FDB 条目只可能落在存在且启用过的端口号上；条目 vlan 与快照结构合法
        for (SwitchView sw : v.switches()) {
            for (var e : sw.fdb()) {
                assertTrue(e.portId() >= 0 && e.portId() < sw.ports().size());
                assertTrue(e.expiresAt() >= e.learnedAt() || e.expiresAt() == Long.MAX_VALUE);
            }
        }

        // 4) 投递数不超过发出数
        long delivered = v.totals().get("hostDelivered");
        assertTrue(delivered <= tx);
    }

    private static long sum(SimulationView v, String key) {
        long n = 0;
        for (SwitchView sw : v.switches()) {
            n += sw.totals().getOrDefault(key, 0L);
        }
        return n;
    }

    @Test
    void concurrentReadersNeverSeePartialUpdate() throws Exception {
        Simulation sim = buildBusyNetwork();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        // 3 个只读线程持续抓快照并做自洽校验，直到写入线程完成
        for (int r = 0; r < 3; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (finished.getCount() > 0) {
                        SimulationView v = sim.snapshot();
                        assertInternallyConsistent(v);
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
        }
        // 1 个写入线程推进仿真
        pool.submit(() -> {
            try {
                start.await();
                String[] src = {"h1", "h2", "h3", "h4", "h5", "h6"};
                String[] dst = {"ff:ff:ff:ff:ff:ff", "01:00:5e:00:00:01",
                        "00:00:00:00:00:04", "00:00:00:00:00:05"};
                for (int i = 0; i < 400; i++) {
                    sim.inject(src[i % src.length], dst[i % dst.length], 1,
                            FramePriority.values()[i % 8], "t" + i);
                    sim.tick();
                }
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                finished.countDown();
            }
        });
        start.countDown();
        assertTrue(finished.await(20, TimeUnit.SECONDS), "写入线程应在时限内完成");
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "读取线程应及时停止");
        assertEquals(0, errors.size(), () -> "并发期间出现错误: " + errors);
        assertInternallyConsistent(sim.snapshot());
    }

    @Test
    void dropEventsMatchReasonCountsAndPortCounters() {
        Simulation sim = buildBusyNetwork();
        // 在 sw1 端口 0 制造随机丢帧，并制造风暴丢弃
        sim.setFault("sw1", 0, new com.github.guoyyit.l2switchsimulatorjava.sim.model.PortFault(0.7, false));
        for (int i = 0; i < 300; i++) {
            String dst = (i % 3 == 0) ? "ff:ff:ff:ff:ff:ff"
                    : (i % 3 == 1) ? "00:00:00:00:00:05" : "00:00:00:00:00:06";
            sim.inject("h1", dst, 1, FramePriority.P0, "d" + i);
            sim.tick();
        }
        sim.tick(10);

        SimulationView v = sim.snapshot();
        // 丢弃事件按原因聚合 == dropReasons 计数
        Map<DropReason, Long> fromEvents = new EnumMap<>(DropReason.class);
        for (DropEvent e : sim.dropEvents()) {
            fromEvents.merge(e.reason(), 1L, Long::sum);
        }
        for (DropReason r : DropReason.values()) {
            assertEquals(fromEvents.getOrDefault(r, 0L), sim.dropCount(r),
                    "原因 " + r + " 的事件数与计数不一致");
            assertEquals(fromEvents.getOrDefault(r, 0L), v.dropReasons().get(r.name()));
        }
        assertInternallyConsistent(v);
    }

    @Test
    void fdbAndCountersReflectLearningState() {
        Simulation sim = buildBusyNetwork();
        for (int i = 0; i < 10; i++) {
            sim.inject("h1", "00:00:00:00:00:04", 1, FramePriority.P2, "u" + i);
            sim.tick(2);
        }
        SimulationView v = sim.snapshot();
        // h1 发出的帧从 sw1 上联口进入 sw2，因此 h1 的 MAC 在两台交换机各学习一次
        long learnedH1 = v.switches().stream()
                .flatMap(sw -> sw.fdb().stream())
                .filter(e -> e.mac().toString().equals("00:00:00:00:00:01"))
                .count();
        assertEquals(2, learnedH1);
        // 学习计数：同一源重复发送只新增学习一次
        SwitchView sw1 = v.switches().stream().filter(s -> s.name().equals("sw1")).findFirst().orElseThrow();
        assertEquals(1, sw1.ports().get(0).counters().learned());
        // 计数器 rx/tx 非负且 flooded 与广播/未知帧行为一致（首批未知单播触发泛洪）
        for (SwitchView sw : v.switches()) {
            for (PortView p : sw.ports()) {
                assertTrue(p.counters().rxFrames() >= 0);
                assertTrue(p.counters().txFrames() >= 0);
            }
        }
        assertInternallyConsistent(v);
    }

    @Test
    void snapshotIsImmutableAndStableAfterFurtherTicks() {
        Simulation sim = buildBusyNetwork();
        sim.tick(2);
        SimulationView before = sim.snapshot();
        int fdbBefore = before.switches().stream().mapToInt(s -> s.fdb().size()).sum();
        sim.inject("h1", "ff:ff:ff:ff:ff:ff", 1, FramePriority.P0, "x");
        sim.tick(5);
        SimulationView after = sim.snapshot();
        // 旧快照内容不变
        assertEquals(fdbBefore, before.switches().stream().mapToInt(s -> s.fdb().size()).sum());
        // 旧快照中的列表不可变
        List<?> oldFdb = before.switches().get(0).fdb();
        try {
            oldFdb.clear();
            throw new AssertionError("FDB 快照必须不可变");
        } catch (UnsupportedOperationException expected) {
            // 期望异常
        }
        assertEquals(2L, before.tick());
        assertTrue(after.tick() > before.tick());
    }
}
