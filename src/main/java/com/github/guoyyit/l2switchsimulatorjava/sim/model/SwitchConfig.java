package com.github.guoyyit.l2switchsimulatorjava.sim.model;

/**
 * 交换机仿真参数。
 *
 * @param agingTicks 转发表老化时间（tick），小于 0 表示不老化
 * @param seed       确定性随机种子
 */
public record SwitchConfig(long agingTicks, long seed) {
    public SwitchConfig {
        if (agingTicks == 0 || agingTicks < -1) {
            throw new IllegalArgumentException("老化时间必须为正或 -1(不老化)");
        }
    }

    public static SwitchConfig defaults() {
        return new SwitchConfig(100, 1L);
    }
}
