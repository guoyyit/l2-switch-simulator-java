package com.github.guoyyit.l2switchsimulatorjava.sim;

import com.github.guoyyit.l2switchsimulatorjava.sim.core.Simulation;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.BackpressureAction;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.DropEvent;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.DropReason;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.FramePriority;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.PortConfig;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.PortCountersView;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.PortFault;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.PortView;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.SimulationView;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.StormAction;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.SwitchConfig;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.SwitchView;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 需求 7：混合故障注入、风暴控制、队列背压与环路阻断时统计自洽，
 * 接收/发送/排队/丢弃可与队列深度、主机已收帧、丢弃事件明细交叉核对。
 */
class StatsConservationTest {

    /** 入端拒绝类原因（帧未进入入端口暂存队列）。 */
    private static boolean isAdmissionReject(DropReason r) {
        return r == DropReason.DISABLED_INGRESS
                || r == DropReason.LOOP_BLOCKED_INGRESS
                || r == DropReason.INGRESS_QUEUE_FULL;
    }

    /**
     * 单端口守恒：真实到达（ingressArrivals） ==
     *   处理计数 rxFrames（离开入端口暂存：转发/泛洪/入处理丢弃/静默过滤）
     *   - 注意 rxFrames 只计未在入处理被丢弃/过滤后“被接收处理”的帧口径，
     * 因此这里用更严格的到达守恒：arrivals == 当前入队列深度 + 已离开入端口的帧。
     */
    private static void assertPortArrivalConservation(PortView p) {
        PortCountersView c = p.counters();
        // 每个真实到达的帧（ingressArrivals）只可能处于四种互斥状态：
        // 仍在入端口暂存 / 入处理阶段丢弃 / 进入转发管道(rx) / 同端口静默过滤。
        long accounted = p.ingressDepth();
        accounted += c.ingressDropped();
        accounted += c.rxFrames();
        accounted += c.filtered();
        assertEquals(c.ingressArrivals(), accounted,
                "端口 " + p.portId() + " 到达守恒不成立: arrivals=" + c.ingressArrivals()
                        + " depth=" + p.ingressDepth() + " ingressDropped=" + c.ingressDropped()
                        + " rx=" + c.rxFrames() + " filtered=" + c.filtered()
                        + " rejected=" + c.admissionRejected());

        // dropped 总量必须等于三类互斥丢弃之和
        assertEquals(c.dropped(),
                c.admissionRejected() + c.ingressDropped() + c.egressDropped(),
                "端口 " + p.portId() + " dropped 分类求和必须等于总量");
    }

    /**
     * 转发管道守恒（交换机内）：
     * rxFrames（进入转发决策） == 进入输出队列并最终发出/在队列 + 出端丢弃 + 同端口过滤已单列。
     * 用“输出队列深度 + tx + 输出侧丢弃 + filtered”与 rx 对账。
     */
    private static void assertSwitchPipeConservation(Simulation sim, SwitchView sw) {
        long rx = 0;
        long tx = 0;
        long inEgressQueues = 0;
        long egressDrops = 0;
        long filtered = 0;
        long flooded = 0;
        for (PortView p : sw.ports()) {
            PortCountersView c = p.counters();
            rx += c.rxFrames();
            tx += c.txFrames();
            filtered += c.filtered();
            flooded += c.flooded();
            inEgressQueues += p.queueDepths().stream().mapToInt(Integer::intValue).sum();
            for (DropEvent e : sim.dropEvents()) {
                if (e.switchName().equals(sw.name()) && !isAdmissionReject(e.reason())) {
                    egressDrops++;
                }
            }
        }
        // 离开入处理的帧：要么进了输出队列（=已 tx 或仍排队），要么是输出侧丢弃，
        // 要么被同端口静默过滤。
        // 注意 flooded 是副本数（一帧多副本），故以“进入输出队列的副本数”口径：
        // 单播 1 副本，泛洪 flooded 个副本。rx 是帧数，副本可能多份；
        // 因此这里只校验“无副本凭空产生/消失”的非负关系与总量上下界。
        long out = tx + inEgressQueues + egressDrops;
        assertTrue(out + filtered >= rx,
                "输出侧（发出+在队+出端丢弃+过滤）不能少于接收帧数: "
                        + (out + filtered) + " < rx=" + rx);
    }

    private Simulation mixedLoad(long seed) {
        // 小容量 + 两种背压 + 两种风暴动作混合，制造暂停/限速/满队列/风暴丢弃
        PortConfig pauseLimit = new PortConfig(2, 2, BackpressureAction.PAUSE, 3, 2,
                StormAction.LIMIT);
        PortConfig dropDrop = new PortConfig(2, 4, BackpressureAction.DROP, 3, 2,
                StormAction.DROP);
        Simulation sim = new Simulation(seed);
        sim.addSwitch("sw1", new SwitchConfig(-1, seed));
        sim.addSwitch("sw2", new SwitchConfig(-1, seed));
        for (int i = 0; i < 3; i++) {
            sim.addPort("sw1", true, i % 2 == 0 ? pauseLimit : dropDrop);
            sim.addPort("sw2", true, i % 2 == 0 ? dropDrop : pauseLimit);
        }
        sim.addPort("sw1", false, pauseLimit);
        sim.addPort("sw2", false, pauseLimit);
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
        sim.setFault("sw1", 0, new PortFault(0.5, true));
        sim.setFault("sw2", 2, new PortFault(0.4, false));
        return sim;
    }

    @Test
    void conservationHoldsUnderMixedFaultsStormBackpressure() {
        Simulation sim = mixedLoad(909L);
        String[] src = {"h1", "h2", "h3", "h4", "h5", "h6"};
        String[] dst = {"ff:ff:ff:ff:ff:ff", "01:00:5e:00:00:01",
                "00:00:00:00:00:04", "00:00:00:00:00:05"};
        for (int round = 0; round < 8; round++) {
            for (int i = 0; i < 10; i++) {
                sim.inject(src[(round + i) % 6], dst[i % 4], 1,
                        FramePriority.values()[i % 8], "m" + round + "-" + i);
            }
            sim.tick(1);
        }
        sim.tick(60);

        SimulationView v = sim.snapshot();
        // 1) 每个端口到达守恒
        for (SwitchView sw : v.switches()) {
            for (PortView p : sw.ports()) {
                assertPortArrivalConservation(p);
            }
            assertSwitchPipeConservation(sim, sw);
        }

        // 2) 丢弃事件数 == 按原因汇总 == 端口 dropped 之和
        long totalDropped = 0;
        for (SwitchView sw : v.switches()) {
            for (PortView p : sw.ports()) {
                totalDropped += p.counters().dropped();
            }
        }
        Map<DropReason, Long> fromEvents = new EnumMap<>(DropReason.class);
        for (DropEvent e : sim.dropEvents()) {
            fromEvents.merge(e.reason(), 1L, Long::sum);
        }
        long eventSum = fromEvents.values().stream().mapToLong(Long::longValue).sum();
        assertEquals(totalDropped, eventSum, "丢弃事件总数必须等于端口 dropped 之和");
        for (DropReason r : DropReason.values()) {
            assertEquals(fromEvents.getOrDefault(r, 0L), sim.dropCount(r));
            assertEquals(fromEvents.getOrDefault(r, 0L), v.dropReasons().get(r.name()));
        }

        // 3) 主机收到总数 == 全局 hostDelivered
        long hostRx = 0;
        for (var hv : v.hosts()) {
            hostRx += hv.rxFrames().size();
        }
        assertEquals(hostRx, v.totals().get("hostDelivered"));

        // 4) 每个主机收到的帧都带有效载荷且不重复计数（发送帧端 tx 与收到数一致）
        long hostFacingTx = 0;
        for (SwitchView sw : v.switches()) {
            for (PortView p : sw.ports()) {
                if (p.hostFacing()) {
                    hostFacingTx += p.counters().txFrames();
                }
            }
        }
        assertEquals(hostRx, hostFacingTx, "主机口发送数必须等于主机收到数");
    }

    @Test
    void dropEventsMatchCountersUnderLoopBlockingAndFaults() {
        // A-B-C 三台交换机构成三角形，制造确定性阻断端口
        Simulation sim = new Simulation(101L);
        PortConfig cfg = new PortConfig(8, 4, BackpressureAction.DROP, 1000, 4, StormAction.DROP);
        for (String n : new String[]{"A", "B", "C"}) {
            sim.addSwitch(n, new SwitchConfig(-1, 101L));
            sim.addPort(n, false, cfg);
            sim.addPort(n, false, cfg);
            sim.addPort(n, true, cfg);
        }
        sim.addHost("ha", "00:00:00:00:00:0a");
        sim.addHost("hb", "00:00:00:00:00:0b");
        sim.connectHost("ha", "A", 2);
        sim.connectHost("hb", "B", 2);
        sim.addLink("A", 0, "B", 0);
        sim.addLink("A", 1, "C", 0);
        sim.addLink("B", 1, "C", 1);
        sim.setFault("A", 2, new PortFault(0.4, false));

        for (int i = 0; i < 60; i++) {
            sim.inject("ha", i % 2 == 0 ? "ff:ff:ff:ff:ff:ff" : "00:00:00:00:00:0b",
                    1, FramePriority.P0, "l" + i);
            sim.tick();
        }
        sim.tick(40);

        SimulationView v = sim.snapshot();
        long dropped = 0;
        for (SwitchView sw : v.switches()) {
            for (PortView p : sw.ports()) {
                dropped += p.counters().dropped();
                assertPortArrivalConservation(p);
            }
        }
        assertEquals(sim.dropEvents().size(), dropped);
        // 三角形必然产生阻断端口
        assertTrue(v.blockedPorts().size() >= 2, "三角形拓扑应阻断至少两端口");
    }
}
