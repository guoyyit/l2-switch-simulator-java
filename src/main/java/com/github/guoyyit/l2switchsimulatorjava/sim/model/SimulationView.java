package com.github.guoyyit.l2switchsimulatorjava.sim.model;

import java.util.List;
import java.util.Map;

/** 整个仿真的不可变状态快照（并发查询下不会看到部分更新）。 */
public record SimulationView(long tick,
                             List<SwitchView> switches,
                             List<HostView> hosts,
                             List<List<String>> links,
                             List<String> blockedPorts,
                             Map<String, Long> dropReasons,
                             Map<String, Long> totals) {
}
