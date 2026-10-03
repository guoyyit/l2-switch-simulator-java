package com.github.guoyyit.l2switchsimulatorjava.sim.model;

/** 帧被丢弃的确定性原因分类。 */
public enum DropReason {
    DISABLED_INGRESS,
    DISABLED_EGRESS,
    FAULT_DROP,
    LOOP_BLOCKED_INGRESS,
    LOOP_BLOCKED_EGRESS,
    STORM_CONTROL,
    QUEUE_FULL,
    INGRESS_QUEUE_FULL,
    NO_MEMBER_PORTS
}
