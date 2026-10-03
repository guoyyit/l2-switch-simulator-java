package com.github.guoyyit.l2switchsimulatorjava.sim.core;

import com.github.guoyyit.l2switchsimulatorjava.sim.log.Event;
import com.github.guoyyit.l2switchsimulatorjava.sim.log.EventLog;
import com.github.guoyyit.l2switchsimulatorjava.sim.log.EventType;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.BackpressureAction;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.FramePriority;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.PortConfig;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.PortFault;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.StormAction;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.SwitchConfig;

import java.util.List;
import java.util.Map;

/**
 * 回放引擎：把事件日志中的刺激事件（建模仿真、配置变更、注入、时钟推进）按序折叠到
 * 一个隔离的 {@link Simulation} 中；转发随机判定由 {@code RecordedDecisionSource} 按日志给出。
 *
 * <p>折叠只重放“外部刺激”，不重放转发结果（DROP / FRAME_DELIVERED）——这些由回放仿真
 * 依据相同刺激与相同判定重新产生，从而验证“转发顺序与丢帧结果逐次一致”。
 */
final class ReplayEngine {

    private ReplayEngine() {
    }

    /**
     * 把日志中 seq &lt;= toSeq 的刺激事件折叠到 replaySim。
     *
     * @return 实际折叠的刺激事件数
     */
    static int fold(Simulation replaySim, EventLog log, long toSeq) {
        List<Event> all = log.all();
        int folded = 0;
        for (Event e : all) {
            if (e.seq() > toSeq) {
                break;
            }
            if (apply(replaySim, e)) {
                folded++;
            }
        }
        return folded;
    }

    /** @return true 表示该事件是刺激事件并已折叠；false 表示结果事件（跳过）。 */
    private static boolean apply(Simulation sim, Event e) {
        Map<String, Object> d = e.data();
        EventType t = e.type();
        switch (t) {
            case SWITCH_ADDED -> sim.addSwitch((String) d.get("name"),
                    new SwitchConfig((Long) d.get("agingTicks"), (Long) d.get("seed")));
            case PORT_ADDED -> sim.addPort((String) d.get("switchName"), (Boolean) d.get("hostFacing"),
                    portConfig(d));
            case PORT_ENABLED -> sim.setPortEnabled((String) d.get("switchName"),
                    (Integer) d.get("portId"), (Boolean) d.get("enabled"));
            case PORT_CONFIGURED -> sim.configurePort((String) d.get("switchName"),
                    (Integer) d.get("portId"), portConfig(d));
            case FAULT_SET -> sim.setFault((String) d.get("switchName"), (Integer) d.get("portId"),
                    new PortFault((Double) d.get("dropRate"), (Boolean) d.get("reorderEnabled")));
            case VLAN_SET -> sim.setVlan((String) d.get("switchName"), (Integer) d.get("portId"),
                    (Integer) d.get("vlan"));
            case HOST_ADDED -> sim.addHost((String) d.get("name"), (String) d.get("mac"));
            case HOST_CONNECTED -> sim.connectHost((String) d.get("hostName"),
                    (String) d.get("switchName"), (Integer) d.get("portId"));
            case HOST_DISCONNECTED -> sim.disconnectHost((String) d.get("hostName"));
            case LINK_ADDED -> sim.addLink((String) d.get("switchA"), (Integer) d.get("portA"),
                    (String) d.get("switchB"), (Integer) d.get("portB"));
            case LINK_REMOVED -> sim.removeLink((String) d.get("switchA"), (Integer) d.get("portA"),
                    (String) d.get("switchB"), (Integer) d.get("portB"));
            case FRAME_INJECTED -> sim.inject((String) d.get("hostName"), (String) d.get("dst"),
                    (Integer) d.get("vlan"), FramePriority.of((Integer) d.get("priority")),
                    (String) d.get("payload"));
            case TICK -> sim.tick();
            default -> {
                return false;
            }
        }
        return true;
    }

    private static PortConfig portConfig(Map<String, Object> d) {
        return new PortConfig((Integer) d.get("capacityPerPriority"), (Integer) d.get("starvationTicks"),
                BackpressureAction.valueOf((String) d.get("backpressure")),
                (Integer) d.get("stormThreshold"), (Integer) d.get("stormWindow"),
                StormAction.valueOf((String) d.get("stormAction")));
    }
}
