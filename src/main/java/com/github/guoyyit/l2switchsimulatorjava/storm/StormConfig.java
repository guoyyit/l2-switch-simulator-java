package com.github.guoyyit.l2switchsimulatorjava.storm;

/**
 * 广播/组播风暴控制配置：每窗口阈值、窗口长度（tick）、超阈处理模式。
 */
public final class StormConfig {

    private int threshold;
    private long windowTicks;
    private StormMode mode;

    public StormConfig() { }

    public StormConfig(int threshold, long windowTicks, StormMode mode) {
        this.threshold = threshold;
        this.windowTicks = windowTicks;
        this.mode = mode;
    }

    public int getThreshold() { return threshold; }
    public void setThreshold(int threshold) { this.threshold = threshold; }
    public long getWindowTicks() { return windowTicks; }
    public void setWindowTicks(long windowTicks) { this.windowTicks = windowTicks; }
    public StormMode getMode() { return mode; }
    public void setMode(StormMode mode) { this.mode = mode; }
}
