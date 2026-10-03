package com.github.guoyyit.l2switchsimulatorjava.sim.log;

import java.util.Map;

/**
 * 一条转发事件的不可变记录。
 *
 * @param seq  单调递增的顺序号（从 0 开始），全程唯一且连续
 * @param tick 事件发生时的逻辑时间
 * @param type 事件类型
 * @param data 事件载荷（帧标识、交换机/端口、判定结果、丢弃原因等）
 */
public record Event(long seq, long tick, EventType type, Map<String, Object> data) {

    public Event {
        data = Map.copyOf(data);
    }

    public static Event of(long seq, long tick, EventType type, Map<String, Object> data) {
        return new Event(seq, tick, type, data == null ? Map.of() : data);
    }
}
