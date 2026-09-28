package com.github.guoyyit.l2switchsimulatorjava.sim.model;

import java.util.List;
import java.util.Map;

/** 单台交换机的不可变状态快照。 */
public record SwitchView(String name,
                         long tick,
                         long agingTicks,
                         boolean enabled,
                         List<PortView> ports,
                         List<FdbEntryView> fdb,
                         Map<String, Long> totals) {
}
