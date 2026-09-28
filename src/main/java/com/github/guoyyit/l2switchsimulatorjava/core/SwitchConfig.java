package com.github.guoyyit.l2switchsimulatorjava.core;

import com.github.guoyyit.l2switchsimulatorjava.port.BackpressureMode;
import com.github.guoyyit.l2switchsimulatorjava.storm.StormConfig;

/**
 * 单台交换机的默认配置（老化、队列深度、背压、风暴控制、转发 TTL）。
 */
public final class SwitchConfig {

    public long agingTicks = 1000L;
    public int defaultQueueCapacity = 64;
    public BackpressureMode backpressureMode = BackpressureMode.DROP;
    public StormConfig stormConfig = new StormConfig(100, 100, com.github.guoyyit.l2switchsimulatorjava.storm.StormMode.DROP);
    public int maxFrameHops = 32;

    public SwitchConfig() { }
}
