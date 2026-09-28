package com.github.guoyyit.l2switchsimulatorjava.sim.model;

import java.util.List;

/** 端口运行状态的不可变快照。 */
public record PortView(int portId,
                       boolean enabled,
                       boolean blocked,
                       boolean hostFacing,
                       boolean paused,
                       int vlan,
                       String attachedHost,
                       PortFault fault,
                       List<Integer> queueDepths,
                       PortCountersView counters) {
}
