package com.github.guoyyit.l2switchsimulatorjava.core;

/**
 * 确定性虚拟时钟：只通过 tick() 前进，所有老化/窗口/暂停均以 tick 计。
 */
public final class SimulationClock {

    private long now;

    public long now() { return now; }
    public long tick() { return ++now; }
    public long advance(long ticks) {
        if (ticks > 0) now += ticks;
        return now;
    }
}
