package com.github.guoyyit.l2switchsimulatorjava.sim.model;

/** 广播/组播风暴超阈值时的动作。 */
public enum StormAction {
    /** 丢弃超出阈值的 BUM 帧。 */
    DROP,
    /** 限速：超出阈值的帧延迟到下一个 tick。 */
    LIMIT
}
