package com.github.guoyyit.l2switchsimulatorjava.tests;

import com.github.guoyyit.l2switchsimulatorjava.core.Network;
import com.github.guoyyit.l2switchsimulatorjava.core.NetworkView;
import com.github.guoyyit.l2switchsimulatorjava.core.PortView;
import com.github.guoyyit.l2switchsimulatorjava.core.SwitchConfig;
import com.github.guoyyit.l2switchsimulatorjava.model.Frame;
import com.github.guoyyit.l2switchsimulatorjava.model.MacAddress;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 需求 7：运行状态查询在并发写入下不得看到部分更新；统计与转发表/丢帧保持自洽。
 */
class ConcurrencyStatsTest {

    /** 并发快照始终结构自洽：统计非负、队列深度合法、端口数稳定。 */
    @Test
    void concurrentSnapshotsAreNeverTorn() throws Exception {
        SwitchConfig cfg = new SwitchConfig();
        cfg.defaultQueueCapacity = 8;
        Network net = new Network(1L);
        net.addSwitch("sw", 5, cfg);

        int writers = 4;
        int readers = 4;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers + readers);
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicReference<Throwable> error = new AtomicReference<>();

        for (int w = 0; w < writers; w++) {
            final int base = w * 1000;
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 3000 && !stop.get(); i++) {
                        MacAddress src = new MacAddress(0x10000L + base + (i % 50));
                        MacAddress dst = new MacAddress(0x20000L + ((i * 7 + base) % 50));
                        net.inject("sw", i % 4, new Frame(base + i, src, dst, i % 8, 0, null));
                        if ((i & 7) == 0) {
                            net.drainHosts();
                        }
                    }
                } catch (Throwable e) {
                    error.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }

        for (int r = 0; r < readers; r++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 5000 && !stop.get(); i++) {
                        assertViewConsistent(net.snapshot());
                    }
                } catch (Throwable e) {
                    error.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }

        start.countDown();
        done.await();
        stop.set(true);
        if (error.get() != null) {
            fail("并发期间出现异常或不一致快照", error.get());
        }
        assertViewConsistent(net.snapshot());
    }

    private void assertViewConsistent(NetworkView view) {
        assertEquals(1, view.switches().size());
        var sw = view.switches().get(0);
        assertEquals(5, sw.portCount());
        assertEquals(5, sw.ports().size());
        for (PortView p : sw.ports()) {
            assertTrue(p.queueSize() >= 0 && p.queueSize() <= p.queueCapacity(),
                    "队列深度越界: " + p.queueSize());
            assertEquals(p.queueSize(), p.outputQueue().size(), "队列深度与内容必须同快照一致");
            var s = p.statistics();
            assertTrue(s.receivedFrames >= 0 && s.droppedFrames >= 0 && s.forwardedFrames >= 0);
            // droppedFrames 必须不小于各原因分类之和
            assertTrue(s.droppedFrames >= s.faultDroppedFrames + s.stormDroppedFrames
                            + s.queueFullDroppedFrames,
                    "丢帧总数与分类不一致: " + p.index());
        }
        // FDB 中每个条目都指向一个存在的端口
        sw.fdb().forEach(e -> assertTrue(e.portIndex() >= 0 && e.portIndex() < 5));
    }

    /** 稳态串行语义校验：在无故障时，收到的帧要么转发要么泛洪要么丢弃，计数闭合。 */
    @Test
    void statsAccountingIsClosed() {
        SwitchConfig cfg = new SwitchConfig();
        cfg.defaultQueueCapacity = 2; // 故意很小，制造背压丢帧
        Network net = new Network(1L);
        net.addSwitch("sw", 3, cfg);
        for (int i = 0; i < 30; i++) {
            net.inject("sw", 0,
                    new Frame(i, new MacAddress(0x10 + i), new MacAddress(0x90 + i), 0, 0, null));
        }
        var ports = net.snapshot().switches().get(0).ports();
        PortView p1 = ports.get(1);
        PortView p2 = ports.get(2);
        // 每个目的未知帧都会向端口 1/2 尝试一次：成功入队(forwarded) 或 队列满丢弃；
        // 成功入队的帧当前要么仍在队列里（本测试不排空），故 forwarded == queueSize。
        for (PortView p : new PortView[]{p1, p2}) {
            assertEquals(p.queueSize(), p.statistics().forwardedFrames,
                    "未排空前 forwarded 等于队列内帧数, 端口 " + p.index());
            assertEquals(p.statistics().forwardedFrames + p.statistics().queueFullDroppedFrames,
                    30,
                    "端口 " + p.index() + " 尝试次数 = 成功入队 + 队满丢弃");
        }
    }
}
