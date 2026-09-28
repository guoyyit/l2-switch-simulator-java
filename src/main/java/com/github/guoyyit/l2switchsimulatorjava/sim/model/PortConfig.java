package com.github.guoyyit.l2switchsimulatorjava.sim.model;

/**
 * 端口配置（不可变）。
 *
 * @param capacityPerPriority 每个优先级输出队列容量
 * @param starvationTicks     高优先级等待超过该 tick 数后低优先级让行
 * @param backpressure        队列满策略
 * @param stormThreshold      每 tick BUM 帧阈值（&gt;=0，0 表示全部压制）
 * @param stormWindow         风暴统计窗口（tick）
 * @param stormAction         超阈值动作
 */
public record PortConfig(int capacityPerPriority,
                         int starvationTicks,
                         BackpressureAction backpressure,
                         int stormThreshold,
                         int stormWindow,
                         StormAction stormAction) {
    public PortConfig {
        if (capacityPerPriority <= 0) {
            throw new IllegalArgumentException("每优先级队列容量必须 > 0");
        }
        if (starvationTicks < 0) {
            throw new IllegalArgumentException("饿死保护 tick 数不能为负");
        }
        if (stormThreshold < 0) {
            throw new IllegalArgumentException("风暴阈值不能为负");
        }
        if (stormWindow <= 0) {
            throw new IllegalArgumentException("风暴窗口必须 > 0");
        }
    }

    public static PortConfig defaults() {
        return new PortConfig(8, 4, BackpressureAction.DROP, 16, 8, StormAction.DROP);
    }
}
