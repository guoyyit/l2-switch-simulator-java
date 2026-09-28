package com.github.guoyyit.l2switchsimulatorjava.storm;

/**
 * 单端口风暴控制器，仅对广播/组播/未知单播泛洪帧计数。
 *
 * 两种模式均为纯 tick 驱动，无随机量，可确定性复现：
 * DROP：固定时间窗计数，窗内超过阈值的帧全部丢弃；
 * POLICE：令牌桶，桶深等于阈值，按 阈值/窗口长度 的速率每 tick 补充令牌，无令牌则丢弃。
 */
public final class StormController {

    private final StormConfig config;

    private long windowStartTick = -1L;
    private long windowCount;

    private double tokens;
    private long lastRefillTick = -1L;

    private long droppedCount;

    public StormController(StormConfig config) {
        this.config = config == null ? new StormConfig() : config;
        this.tokens = this.config.getThreshold();
    }

    public StormConfig getConfig() { return config; }

    /** 尝试放行一帧；返回 true 表示应丢弃。 */
    public boolean shouldDrop(long nowTick) {
        boolean pass = config.getMode() == StormMode.POLICE
                ? policePass(nowTick)
                : windowPass(nowTick);
        if (!pass) {
            droppedCount++;
        }
        return !pass;
    }

    private boolean windowPass(long nowTick) {
        long w = Math.max(1L, config.getWindowTicks());
        if (windowStartTick < 0L) {
            windowStartTick = nowTick;
        }
        if (nowTick >= windowStartTick + w) {
            long elapsedWindows = (nowTick - windowStartTick) / w;
            windowStartTick += elapsedWindows * w;
            windowCount = 0L;
        }
        if (windowCount < config.getThreshold()) {
            windowCount++;
            return true;
        }
        return false;
    }

    private boolean policePass(long nowTick) {
        refillUntil(nowTick);
        if (tokens >= 1.0d) {
            tokens -= 1.0d;
            return true;
        }
        return false;
    }

    /** 每个虚拟 tick 推进令牌桶（无流量时也持续补充）。 */
    public void tick(long nowTick) {
        if (config.getMode() == StormMode.POLICE) {
            refillUntil(nowTick);
        }
    }

    /**
     * 按虚拟时钟累计令牌：从上次推进位置逐 tick 补充，保证整数/小数时刻均确定。
     * 一帧的到达本身不推进时间，时间只由 Network.step 驱动。
     */
    private void refillUntil(long nowTick) {
        long w = Math.max(1L, config.getWindowTicks());
        if (lastRefillTick < 0L) {
            lastRefillTick = nowTick;
            return;
        }
        while (lastRefillTick < nowTick) {
            lastRefillTick++;
            tokens = Math.min(config.getThreshold(),
                    tokens + (double) config.getThreshold() / w);
        }
    }

    public long droppedCount() { return droppedCount; }

    public void reset() {
        windowStartTick = -1L;
        windowCount = 0L;
        tokens = config.getThreshold();
        lastRefillTick = -1L;
        droppedCount = 0L;
    }
}
