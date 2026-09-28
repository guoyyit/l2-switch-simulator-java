package com.github.guoyyit.l2switchsimulatorjava.sim.core;

import com.github.guoyyit.l2switchsimulatorjava.sim.model.StormAction;

/**
 * 端口级广播/组播风暴控制。
 *
 * <p>统计最近 {@code windowTicks} 个 tick 内每个 tick 观察到的 BUM 帧数（滑动窗口），
 * 当窗口内总数超过 {@code threshold} 时：
 * <ul>
 *   <li>{@link StormAction#DROP}：超出的帧立即丢弃；</li>
 *   <li>{@link StormAction#LIMIT}：超出的帧延迟到下一 tick 再做决策（限速）。</li>
 * </ul>
 * 阈值为 0 表示每个窗口内不允许任何 BUM 帧通过。
 * 状态更新与读取都在交换机锁内进行，结果仅取决于调用次序（确定性）。
 */
public final class StormGuard {

    public enum Decision {
        ALLOW, DROP, LIMIT
    }

    private final int threshold;
    private final int windowTicks;
    private final StormAction action;
    private final long[] counts;
    private int head;
    private long filledUpToTick = -1L;
    private long sum;

    public StormGuard(int threshold, int windowTicks, StormAction action) {
        if (threshold < 0) {
            throw new IllegalArgumentException("风暴阈值不能为负");
        }
        if (windowTicks <= 0) {
            throw new IllegalArgumentException("风暴窗口必须 > 0");
        }
        this.threshold = threshold;
        this.windowTicks = windowTicks;
        this.action = action;
        this.counts = new long[windowTicks];
    }

    private void advanceTo(long now) {
        if (filledUpToTick == -1L) {
            filledUpToTick = now;
            return;
        }
        long skip = now - filledUpToTick;
        for (long i = 0; i < skip && i < windowTicks; i++) {
            long old = counts[head];
            counts[head] = 0;
            sum -= old;
            head = (head + 1) % windowTicks;
        }
        if (skip >= windowTicks) {
            for (int i = 0; i < windowTicks; i++) {
                counts[i] = 0;
            }
            sum = 0;
            head = 0;
        }
        filledUpToTick = now;
    }

    /** 登记一个 BUM 帧并给出本帧决策。 */
    public Decision observe(long now) {
        advanceTo(now);
        if (sum < threshold) {
            counts[head]++;
            sum++;
            return Decision.ALLOW;
        }
        if (action == StormAction.LIMIT) {
            // 帧由调用方保留在入端口队首，下 tick 重新走判定
            return Decision.LIMIT;
        }
        return Decision.DROP;
    }

    /**
     * 查询当前 tick 是否超阈值且处于限速态（不修改计数）。
     * 供交换机在处理被延迟帧时使用。
     */
    public boolean isSaturated(long now) {
        advanceTo(now);
        return sum >= threshold;
    }

    public int threshold() {
        return threshold;
    }
}
