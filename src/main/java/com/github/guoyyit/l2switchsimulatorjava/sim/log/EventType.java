package com.github.guoyyit.l2switchsimulatorjava.sim.log;

/**
 * 影响转发结果的事件类型。
 *
 * <p>事件分两类：
 * <ul>
 *   <li>刺激事件（SWITCH_ADDED / PORT_ADDED / ... / FRAME_INJECTED / TICK）：
 *       由外部配置变更、注入与时钟推进产生，回放时按序折叠即可重建仿真；</li>
 *   <li>决策与结果事件（FAULT_DECISION / REORDER_DECISION / DROP / FRAME_DELIVERED）：
 *       转发过程中产生的随机判定与最终结果，用于核对与导出。</li>
 * </ul>
 */
public enum EventType {
    SWITCH_ADDED,
    PORT_ADDED,
    PORT_ENABLED,
    PORT_CONFIGURED,
    FAULT_SET,
    VLAN_SET,
    HOST_ADDED,
    HOST_CONNECTED,
    HOST_DISCONNECTED,
    LINK_ADDED,
    LINK_REMOVED,
    FRAME_INJECTED,
    TICK,
    FAULT_DECISION,
    REORDER_DECISION,
    DROP,
    FRAME_DELIVERED
}
