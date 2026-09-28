package com.github.guoyyit.l2switchsimulatorjava.fdb;

/**
 * 转发表条目失效原因。
 */
public enum FdbInvalidationReason {
    AGED_OUT,
    PORT_DISABLED,
    VLAN_CHANGED,
    TOPOLOGY_CHANGE
}
