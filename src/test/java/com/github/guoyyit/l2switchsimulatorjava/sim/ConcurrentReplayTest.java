package com.github.guoyyit.l2switchsimulatorjava.sim;

import com.github.guoyyit.l2switchsimulatorjava.sim.core.Simulation;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.BackpressureAction;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.FramePriority;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.PortConfig;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.PortFault;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.PortView;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.ReplayResult;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.SimulationView;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.StormAction;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.SwitchConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 需求 3/9：回放不影响在线推进；并发读取回放快照与在线快照都看不到部分更新。
 */
class ConcurrentReplayTest {

    private Simulation build() {
        PortConfig cfg = new PortConfig(2, 2, BackpressureAction.PAUSE, 3, 2, StormAction.LIMIT);
        Simulation sim = new Simulation(55L);
        sim.addSwitch("sw1", new SwitchConfig(50, 55L));
        sim.addSwitch("sw2", new SwitchConfig(50, 55L));
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
        sim.setFault("sw1", 0, new PortFault(0.3, true));
        return sim;
    }

    private static void assertConsistent(SimulationView v) {
        long rx = 0;
        long dropped = 0;
        for (var sw : v.switches()) {
            long swRx = 0;
            long swDropped = 0;
            for (PortView p : sw.ports()) {
                swRx += p.counters().rxFrames();
                swDropped += p.counters().dropped();
            }
            assertEquals(sw.totals().get("rxFrames"), swRx);
            assertEquals(sw.totals().get("dropped"), swDropped);
            rx += swRx;
            dropped += swDropped;
        }
        assertEquals(v.totals().get("rxFrames"), rx);
        assertEquals(v.totals().get("dropped"), dropped);
        long byReason = v.dropReasons().values().stream().mapToLong(Long::longValue).sum();
        assertEquals(dropped, byReason);
    }

    @Test
    void replayRunsConcurrentlyWithLiveAdvanceAndReaders() throws Exception {
        Simulation sim = build();
        // 先跑出一段历史
        String[] src = {"h1", "h2", "h3", "h4", "h5", "h6"};
        String[] dst = {"ff:ff:ff:ff:ff:ff", "01:00:5e:00:00:01",
                "00:00:00:00:00:04", "00:00:00:00:00:05"};
        for (int i = 0; i < 40; i++) {
            sim.inject(src[i % 6], dst[i % 4], 1, FramePriority.values()[i % 8], "h" + i);
            sim.tick();
        }

        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch writerDone = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        // 1 个写入线程持续推进在线仿真
        pool.submit(() -> {
            try {
                start.await();
                for (int i = 40; i < 200; i++) {
                    sim.inject(src[i % 6], dst[i % 4], 1, FramePriority.values()[i % 8], "c" + i);
                    sim.tick();
                }
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                writerDone.countDown();
            }
        });

        // 2 个线程反复全量回放并校验结果自洽
        for (int r = 0; r < 2; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (writerDone.getCount() > 0) {
                        ReplayResult rr = sim.replay();
                        assertConsistent(rr.snapshot());
                        assertTrue(rr.matched());
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
        }

        // 2 个只读线程抓在线快照
        for (int r = 0; r < 2; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (writerDone.getCount() > 0) {
                        assertConsistent(sim.snapshot());
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
        }

        start.countDown();
        assertTrue(writerDone.await(30, TimeUnit.SECONDS), "写入线程应在时限内完成");
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "其它线程应及时停止");
        assertEquals(0, errors.size(), () -> "并发期间出现错误: " + errors);

        // 写入停止后，最终状态可成功回放（此时在线不再变化）
        SimulationView live = sim.snapshot();
        assertEquals(live, sim.replay().snapshot());
    }
}
