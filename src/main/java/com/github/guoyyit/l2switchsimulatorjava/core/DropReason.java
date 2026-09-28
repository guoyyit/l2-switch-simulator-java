package com.github.guoyyit.l2switchsimulatorjava.core;

/**
 * 丢帧原因分类，用于统计与故障诊断。
 */
public enum DropReason {
    PORT_DISABLED,
    PORT_BLOCKED,
    PORT_PAUSED,
    VLAN_MISMATCH,
    FAULT_INJECTION,
    QUEUE_FULL,
    STORM_CONTROL,
    TTL_EXPIRED,
    NO_OUTPUT_PORTS
}
