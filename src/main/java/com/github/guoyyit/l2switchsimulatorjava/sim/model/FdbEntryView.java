package com.github.guoyyit.l2switchsimulatorjava.sim.model;

/** 转发表条目的不可变快照。 */
public record FdbEntryView(MacAddress mac, int portId, int vlan, long learnedAt, long expiresAt) {
}
