package com.github.guoyyit.l2switchsimulatorjava.sim.model;

import java.util.List;

/** 主机的不可变快照。 */
public record HostView(String name, MacAddress mac, String switchName, int portId, List<Frame> rxFrames) {
}
