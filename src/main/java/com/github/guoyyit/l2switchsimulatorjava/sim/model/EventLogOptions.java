package com.github.guoyyit.l2switchsimulatorjava.sim.model;

/**
 * 事件记录配置。
 *
 * @param enabled 是否开启事件记录；关闭时转发行为与统计与开启时完全一致，
 *                只是不保留事件、也不支持回放
 * @param capacity 事件容量上限（&gt;=1）；记录满后采用确定的“停止保留新事件”策略
 *                 （{@code DROP_NEW}），绝不阻塞仿真线程、绝不形成背压。
 *                 {@link #UNLIMITED} 表示不设上限
 */
public record EventLogOptions(boolean enabled, long capacity) {

    /** 容量哨兵：不设上限。 */
    public static final long UNLIMITED = 0L;

    public EventLogOptions {
        if (capacity < 0) {
            throw new IllegalArgumentException("事件容量上限不能为负: " + capacity);
        }
    }

    public static EventLogOptions enabled(long capacity) {
        return new EventLogOptions(true, capacity);
    }

    public static EventLogOptions disabled() {
        return new EventLogOptions(false, UNLIMITED);
    }

    public static EventLogOptions defaults() {
        return new EventLogOptions(true, UNLIMITED);
    }
}
