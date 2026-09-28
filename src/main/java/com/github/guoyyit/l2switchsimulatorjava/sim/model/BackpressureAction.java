package com.github.guoyyit.l2switchsimulatorjava.sim.model;

/** 输出队列满时的背压策略。 */
public enum BackpressureAction {
    /** 直接丢弃。 */
    DROP,
    /** 暂停入端口，待队列有空位后恢复。 */
    PAUSE
}
