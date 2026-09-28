package com.github.guoyyit.l2switchsimulatorjava.tests;

import com.github.guoyyit.l2switchsimulatorjava.core.Network;
import com.github.guoyyit.l2switchsimulatorjava.core.PortView;
import com.github.guoyyit.l2switchsimulatorjava.core.SwitchConfig;
import com.github.guoyyit.l2switchsimulatorjava.model.Frame;
import com.github.guoyyit.l2switchsimulatorjava.model.MacAddress;
import com.github.guoyyit.l2switchsimulatorjava.port.BackpressureMode;
import com.github.guoyyit.l2switchsimulatorjava.port.PriorityFrameQueue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 需求 5：队列深度上限、满时丢弃或暂停入端口、高优先级不被低优先级饿死。
 */
class BackpressureQueueTest {

    private MacAddress mac(int v) {
        return new MacAddress(v);
    }

    @Test
    void queueCapacityAndDropMode() {
        SwitchConfig cfg = new SwitchConfig();
        cfg.defaultQueueCapacity = 3;
        cfg.backpressureMode = BackpressureMode.DROP;
        Network net = new Network(1L);
        net.addSwitch("sw", 4, cfg);

        // 让目的地址保持未知：每帧都泛洪到端口 1，使其队列快速填满
        for (int i = 0; i < 5; i++) {
            net.inject("sw", 0, new Frame(100 + i, mac(0x10 + i), mac(0x20 + i), 0, 0, null));
        }
        PortView p1 = net.snapshot().switches().get(0).ports().get(1);
        assertEquals(3, p1.queueSize(), "队列深度受限");
        assertEquals(2, p1.statistics().queueFullDroppedFrames, "满后丢弃 2 帧");
        assertEquals(2, p1.statistics().droppedFrames);
        // 丢帧诊断
        assertTrue(net.recentDrops().stream().anyMatch(d -> d.reason().equals("QUEUE_FULL")));
    }

    @Test
    void pauseModePausesIngress() {
        SwitchConfig cfg = new SwitchConfig();
        cfg.defaultQueueCapacity = 2;
        cfg.backpressureMode = BackpressureMode.PAUSE;
        Network net = new Network(1L);
        net.addSwitch("sw", 4, cfg);

        // 打满端口 1 的输出队列（源地址需要是已知单播才能触发"暂停入端口"的源定位）
        // 先在 sw 上学到源 mac：用它发一帧即可
        net.inject("sw", 0, new Frame(1, mac(0x10), mac(0x90), 0, 0, null));
        net.drainHosts();
        // 再以相同源向不同未知目的发，全部泛洪到 1/2/3，很快打满
        net.inject("sw", 0, new Frame(2, mac(0x10), mac(0x91), 0, 0, null));
        net.inject("sw", 0, new Frame(3, mac(0x10), mac(0x92), 0, 0, null));
        net.inject("sw", 0, new Frame(4, mac(0x10), mac(0x93), 0, 0, null));

        PortView p0 = net.snapshot().switches().get(0).ports().get(0);
        assertTrue(p0.paused() || p0.statistics().pausedIngressEvents > 0,
                "PAUSE 策略下应暂停入端口或记录暂停事件");

        // 暂停期间从该口注入被拒
        int dropsBefore = net.recentDrops().size();
        net.inject("sw", 0, new Frame(5, mac(0x10), mac(0x94), 0, 0, null));
        assertTrue(net.recentDrops().size() > dropsBefore);

        // 时钟推进超过暂停窗口后恢复
        for (int i = 0; i < 6; i++) {
            net.step();
        }
        net.drainHosts();
        PortView p0After = net.snapshot().switches().get(0).ports().get(0);
        assertFalse(p0After.paused());
    }

    @Test
    void strictPriorityScheduling() {
        PriorityFrameQueue q = new PriorityFrameQueue(100);
        q.enqueue(new Frame(1, mac(1), mac(2), 0, 0, null), 0L);
        q.enqueue(new Frame(2, mac(1), mac(2), 0, 0, null), 0L);
        q.enqueue(new Frame(3, mac(1), mac(2), 7, 0, null), 0L);
        assertEquals(3, q.dequeue(0L).id()); // 高优先级先出
        assertEquals(1, q.dequeue(0L).id());
        assertEquals(2, q.dequeue(0L).id());
    }

    @Test
    void lowPriorityCannotBeStarvedForever() {
        // 防饿死阈值设为 4 tick
        PriorityFrameQueue q = new PriorityFrameQueue(100, 4L);
        List<Long> out = new ArrayList<>();
        long t = 0L;
        q.enqueue(new Frame(1, mac(1), mac(2), 0, 0, null), t); // 低优先级帧 t=0 入队
        // 之后每个 tick 都有新的高优先级帧
        for (int i = 0; i < 10; i++) {
            t++;
            q.enqueue(new Frame(10 + i, mac(1), mac(2), 7, 0, null), t);
            out.add(q.dequeue(t).id());
        }
        assertTrue(out.contains(1L),
                "即使持续有高优先级帧，低优先级帧在等待阈值后必须被补偿调度，实际=" + out);
        // 且补偿发生在阈值之后不久
        int idx = out.indexOf(1L);
        assertTrue(idx <= 6, "低优先级帧补偿位置不应过晚: " + idx);
    }
}
