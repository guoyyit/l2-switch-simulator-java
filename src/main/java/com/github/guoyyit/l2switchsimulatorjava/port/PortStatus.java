package com.github.guoyyit.l2switchsimulatorjava.port;

/**
 * 端口管理状态：启用、手工禁用、被环路防护阻断。
 */
public enum PortStatus {
    ENABLED,
    DISABLED,
    BLOCKED
}
