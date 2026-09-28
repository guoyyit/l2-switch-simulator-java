package com.github.guoyyit.l2switchsimulatorjava.sim;

import com.github.guoyyit.l2switchsimulatorjava.sim.core.Simulation;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.BackpressureAction;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.Frame;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.FramePriority;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.PortConfig;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.SwitchConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 需求 5：端口队列上限、队列满丢弃/暂停、严格优先级 + 防饿死。 */
class BackpressureTest {

    private Simulation oneSwitchTwoHosts(PortConfig config) {
        Simulation sim = new Simulation(3L);
        sim.addSwitch("sw", new SwitchConfig(-1, 3L));
        sim.addPort("sw", true, config);
        sim.addPort("sw", true, config);
        sim.addHost("src", "00:00:00:00:00:01");
        sim.addHost("dst", "00:00:00:00:00:02");
        sim.connectHost("src", "sw", 0);
        sim.connectHost("dst", "sw", 1);
        return sim;
    }

    /** 让目的 MAC 被学习为端口 1：后续为已知单播。 */
    private void learn(Simulation sim) {
        sim.inject("dst", "00:00:00:00:00:01", 1, FramePriority.P0, "learn");
        sim.tick(2);
    }

    @Test
    void overflowDropsWhenPolicyIsDrop() {
        PortConfig cfg = new PortConfig(2, 4, BackpressureAction.DROP, 1000, 8,
                com.github.guoyyit.l2switchsimulatorjava.sim.model.StormAction.DROP);
        Simulation sim = oneSwitchTwoHosts(cfg);
        learn(sim);
        // 一次 tick 内从源注入 6 帧已知单播：输出 P0 队列容量 2 -> 4 帧应丢弃
        for (int i = 0; i < 6; i++) {
            sim.inject("src", "00:00:00:00:00:02", 1, FramePriority.P0, "d" + i);
        }
        sim.tick(1);
        assertEquals(4, sim.dropCount(com.github.guoyyit.l2switchsimulatorjava.sim.model.DropReason.QUEUE_FULL));
        var port0 = sim.snapshot().switches().get(0).ports().get(0);
        assertEquals(4, port0.counters().dropped());
    }

    @Test
    void overflowPausesIngressWhenPolicyIsPause() {
        PortConfig cfg = new PortConfig(2, 4, BackpressureAction.PAUSE, 1000, 8,
                com.github.guoyyit.l2switchsimulatorjava.sim.model.StormAction.DROP);
        Simulation sim = oneSwitchTwoHosts(cfg);
        learn(sim);
        for (int i = 0; i < 6; i++) {
            sim.inject("src", "00:00:00:00:00:02", 1, FramePriority.P0, "p" + i);
        }
        sim.tick(1);
        // PAUSE：不允许队列满导致的丢弃；入端口应处于暂停态
        assertEquals(0, sim.dropCount(com.github.guoyyit.l2switchsimulatorjava.sim.model.DropReason.QUEUE_FULL));
        var view = sim.snapshot().switches().get(0);
        assertTrue(view.ports().get(0).paused(), "入端口应处于背压暂停");
        assertTrue(view.ports().get(0).counters().pausedTicks() >= 1);

        // 持续推进：每个 tick 输出一帧，暂停期间不丢帧，最终 6 帧全部送达
        sim.tick(20);
        long delivered = sim.hostReceived("dst").stream()
                .filter(f -> f.payload().startsWith("p")).count();
        assertEquals(6, delivered);
        assertFalse(sim.snapshot().switches().get(0).ports().get(0).paused());
    }

    @Test
    void strictPriorityServesHighPriorityFirst() {
        PortConfig cfg = new PortConfig(8, 100000, BackpressureAction.DROP, 1000, 8,
                com.github.guoyyit.l2switchsimulatorjava.sim.model.StormAction.DROP);
        Simulation sim = oneSwitchTwoHosts(cfg);
        learn(sim);
        // 同一 tick 注入：3 个低优先级，再 1 个高优先级
        sim.inject("src", "00:00:00:00:00:02", 1, FramePriority.P0, "low-1");
        sim.inject("src", "00:00:00:00:00:02", 1, FramePriority.P0, "low-2");
        sim.inject("src", "00:00:00:00:00:02", 1, FramePriority.P0, "low-3");
        sim.inject("src", "00:00:00:00:00:02", 1, FramePriority.P6, "high");
        sim.tick(1); // 高优先级首帧先出（它排在低优先级后入队）
        Frame first = sim.hostReceived("dst").stream()
                .filter(f -> f.payload().startsWith("low-") || f.payload().equals("high"))
                .findFirst().orElseThrow();
        assertEquals("high", first.payload());
    }

    @Test
    void lowPriorityCannotStarveForever() {
        // starvationTicks=2：低优先级帧等待超过 2 tick 后有效优先级抬升，必然得到服务
        PortConfig cfg = new PortConfig(64, 2, BackpressureAction.DROP, 1000, 8,
                com.github.guoyyit.l2switchsimulatorjava.sim.model.StormAction.DROP);
        Simulation sim = oneSwitchTwoHosts(cfg);
        learn(sim);
        // 先放 4 个最低优先级帧
        for (int i = 0; i < 4; i++) {
            sim.inject("src", "00:00:00:00:00:02", 1, FramePriority.P0, "L" + i);
        }
        // 后续 20 个 tick 每 tick 注入一个中高优先级帧
        List<String> order = new ArrayList<>();
        sim.tick(1);
        for (int t = 0; t < 20; t++) {
            sim.inject("src", "00:00:00:00:00:02", 1, FramePriority.P3, "H" + t);
            sim.tick(1);
        }
        sim.tick(10);
        for (Frame f : sim.hostReceived("dst")) {
            order.add(f.payload());
        }
        // 4 个低优先级帧最终全部送达（没有被高优先级无限饿死）
        for (int i = 0; i < 4; i++) {
            assertTrue(order.contains("L" + i), "低优先级帧 L" + i + " 被饿死");
        }
        // 且至少一个低优先级帧在若干高优先级帧之后才被服务（证明抬升机制在起作用）
        int firstHigh = order.indexOf("H0");
        int lastLowIndex = -1;
        for (int i = 0; i < 4; i++) {
            lastLowIndex = Math.max(lastLowIndex, order.indexOf("L" + i));
        }
        assertTrue(lastLowIndex > firstHigh, "低优先级帧应在高优先级流期间被穿插调度");
    }

    @Test
    void independentQueuesPerPriorityHaveBoundedDepth() {
        PortConfig cfg = new PortConfig(1, 4, BackpressureAction.DROP, 1000, 8,
                com.github.guoyyit.l2switchsimulatorjava.sim.model.StormAction.DROP);
        Simulation sim = oneSwitchTwoHosts(cfg);
        learn(sim);
        // P0 两帧 -> 一入队一丢弃；P1 两帧 -> 一入队一丢弃：两个队列相互独立
        sim.inject("src", "00:00:00:00:00:02", 1, FramePriority.P0, "a0");
        sim.inject("src", "00:00:00:00:00:02", 1, FramePriority.P0, "a1");
        sim.tick(1); // a0 溢出后被丢弃，a1 在 tick 末出队送达
        assertEquals(1, sim.dropCount(com.github.guoyyit.l2switchsimulatorjava.sim.model.DropReason.QUEUE_FULL));
        sim.inject("src", "00:00:00:00:00:02", 1, FramePriority.P1, "b0");
        sim.inject("src", "00:00:00:00:00:02", 1, FramePriority.P1, "b1");
        sim.tick(1); // b1 溢出，b0 在 tick 末出队送达
        assertEquals(2, sim.dropCount(com.github.guoyyit.l2switchsimulatorjava.sim.model.DropReason.QUEUE_FULL));
        var depths = sim.snapshot().switches().get(0).ports().get(1).queueDepths();
        List<String> delivered = sim.hostReceived("dst").stream()
                .filter(f -> List.of("a0", "a1", "b0", "b1").contains(f.payload()))
                .map(Frame::payload).toList();
        assertEquals(List.of("a0", "b0"), delivered, "每优先级容量独立，每 tick 各调度一次");
        assertEquals(0, depths.stream().mapToInt(Integer::intValue).sum());
    }
}
