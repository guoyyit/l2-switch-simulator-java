package com.github.guoyyit.l2switchsimulatorjava.core;

/**
 * 可回放事件（记录类型）。
 * INJECT：在 tick 时刻向 switch/port 注入源主机帧；
 * TICK：推进虚拟时钟；STABLE：交付各输出队列到主机。
 */
public record SimulationEvent(Type type, long atTick, String switchName, int portIndex,
                              com.github.guoyyit.l2switchsimulatorjava.model.Frame frame) {

    public enum Type { INJECT, TICK, DRAIN }

    public static SimulationEvent inject(long atTick, String switchName, int portIndex,
                                         com.github.guoyyit.l2switchsimulatorjava.model.Frame frame) {
        return new SimulationEvent(Type.INJECT, atTick, switchName, portIndex, frame);
    }

    public static SimulationEvent tick(long atTick) {
        return new SimulationEvent(Type.TICK, atTick, null, -1, null);
    }

    public static SimulationEvent drain(long atTick) {
        return new SimulationEvent(Type.DRAIN, atTick, null, -1, null);
    }
}
