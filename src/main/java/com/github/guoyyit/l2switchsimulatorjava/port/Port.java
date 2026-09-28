package com.github.guoyyit.l2switchsimulatorjava.port;

import com.github.guoyyit.l2switchsimulatorjava.core.DeterministicRandom;
import com.github.guoyyit.l2switchsimulatorjava.model.Frame;

/**
 * 逻辑交换端口：独立输入/输出队列、启停、STP 阻断、暂停背压、故障注入与统计。
 *
 * 状态由两个正交布尔量派生：
 *   adminUp=false -> DISABLED；blocked=true -> BLOCKED；否则 ENABLED。
 */
public final class Port {

    private final int index;
    private PortConfig config;
    private final PriorityFrameQueue outputQueue;
    private boolean adminUp = true;
    private boolean blocked = false;
    private FaultProfile faultProfile;
    private final PortStatistics statistics = new PortStatistics();
    private long pausedUntilTick;
    private DeterministicRandom rng = new DeterministicRandom(1L);

    public Port(int index, PortConfig config) {
        this.index = index;
        this.config = config;
        this.outputQueue = new PriorityFrameQueue(config.getQueueCapacity());
        this.faultProfile = new FaultProfile();
    }

    public int getIndex() { return index; }
    public PortConfig getConfig() { return config; }

    /** 修改配置（队列容量在建队时确定，不随配置热变更）。 */
    public void setConfig(PortConfig config) { this.config = config; }

    public PortStatus getStatus() {
        if (!adminUp) {
            return PortStatus.DISABLED;
        }
        return blocked ? PortStatus.BLOCKED : PortStatus.ENABLED;
    }

    public PriorityFrameQueue getOutputQueue() { return outputQueue; }
    public FaultProfile getFaultProfile() { return faultProfile; }
    public void setFaultProfile(FaultProfile faultProfile) {
        this.faultProfile = faultProfile == null ? new FaultProfile() : faultProfile;
    }
    public PortStatistics getStatistics() { return statistics; }
    public long getPausedUntilTick() { return pausedUntilTick; }

    /** 由交换机在构造时注入确定随机流（不同端口不同子序列）。 */
    public void initRandom(DeterministicRandom random) { this.rng = random; }

    public boolean isAdminUp() { return adminUp; }
    public boolean isBlocked() { return blocked; }

    /** 是否可转发：管理启用且未被 STP 阻断。 */
    public boolean isForwarding() { return adminUp && !blocked; }

    public void enable() {
        this.adminUp = true;
    }

    public void disable() {
        this.adminUp = false;
        this.pausedUntilTick = 0L;
    }

    public void setBlocked(boolean blocked) {
        this.blocked = blocked;
        if (blocked) {
            this.pausedUntilTick = 0L;
        }
    }

    /** 暂停入方向到指定 tick（PAUSE 背压策略使用）。 */
    public void pause(long untilTick) {
        this.pausedUntilTick = Math.max(this.pausedUntilTick, untilTick);
    }

    public boolean isPaused(long nowTick) {
        if (nowTick >= pausedUntilTick) {
            pausedUntilTick = 0L;
            return false;
        }
        return true;
    }

    /** 入方向故障：按配置概率丢帧。返回 true 表示被注入丢弃。 */
    public boolean injectFaultOnIngress(Frame frame) {
        double p = faultProfile.getDropProbability();
        if (p <= 0.0) {
            return false;
        }
        if (p >= 1.0 || rng.nextDouble() < p) {
            statistics.faultDroppedFrames++;
            statistics.droppedFrames++;
            return true;
        }
        return false;
    }

    /** 出方向故障丢帧判定。 */
    public boolean shouldDropEgress(Frame frame) {
        double p = faultProfile.getDropProbability();
        if (p <= 0.0) {
            return false;
        }
        if (p >= 1.0 || rng.nextDouble() < p) {
            statistics.faultDroppedFrames++;
            statistics.droppedFrames++;
            return true;
        }
        return false;
    }

    /** 出方向错序判定。 */
    public boolean shouldReorderEgress(Frame frame) {
        if (faultProfile.getOutOfOrderProbability() <= 0.0) {
            return false;
        }
        return rng.nextDouble() < faultProfile.getOutOfOrderProbability();
    }

    /** 输出队列入队，返回 false 表示满（由交换机按背压策略处理）。 */
    public boolean enqueueOutput(Frame frame, long nowTicks) {
        return outputQueue.enqueue(frame, nowTicks);
    }

    public boolean outputQueueFull() {
        return outputQueue.isFull();
    }

    /**
     * 出队下一交付帧：
     * 依次对调度器选中的帧做出方向丢帧抽签、错序抽签；
     * 若触发错序且队列中仍有后继帧，则后继帧先交付，当前帧放回其子队列尾部。
     * 被丢弃的帧不会因错序逻辑重新入队。无帧返回 null。
     */
    public Frame dequeueOutput(long nowTicks) {
        Frame first = outputQueue.dequeue(nowTicks);
        if (first == null) {
            return null;
        }
        if (shouldDropEgress(first)) {
            return null;
        }
        if (shouldReorderEgress(first) && !outputQueue.isEmpty()) {
            Frame second = outputQueue.dequeue(nowTicks);
            if (second != null && !shouldDropEgress(second)) {
                // first 放回其优先级子队列尾部（已取出两帧，容量必然足够），second 先交付。
                outputQueue.enqueue(first, nowTicks);
                statistics.outOfOrderEvents++;
                return second;
            }
            if (second != null) {
                // second 被故障丢弃，first 正常交付。
                return first;
            }
        }
        return first;
    }
}
