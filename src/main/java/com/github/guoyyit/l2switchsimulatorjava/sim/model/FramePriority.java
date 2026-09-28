package com.github.guoyyit.l2switchsimulatorjava.sim.model;

/** 帧优先级，0 最低，7 最高。 */
public enum FramePriority {
    P0(0), P1(1), P2(2), P3(3), P4(4), P5(5), P6(6), P7(7);

    private final int value;

    FramePriority(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public static FramePriority of(int v) {
        if (v < 0 || v > 7) {
            throw new IllegalArgumentException("优先级必须在 0..7 之间: " + v);
        }
        return values()[v];
    }
}
