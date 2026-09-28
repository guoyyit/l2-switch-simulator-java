package com.github.guoyyit.l2switchsimulatorjava.port;

/**
 * 端口统计计数器（公开可变字段，便于引擎侧直接累加）。
 */
public final class PortStatistics {

    public long receivedFrames;
    public long forwardedFrames;
    public long floodedFrames;
    public long droppedFrames;
    public long pausedIngressEvents;
    public long faultDroppedFrames;
    public long outOfOrderEvents;
    public long stormDroppedFrames;
    public long queueFullDroppedFrames;

    public PortStatistics() {
    }

    private PortStatistics(long receivedFrames, long forwardedFrames, long floodedFrames,
                           long droppedFrames, long pausedIngressEvents, long faultDroppedFrames,
                           long outOfOrderEvents, long stormDroppedFrames, long queueFullDroppedFrames) {
        this.receivedFrames = receivedFrames;
        this.forwardedFrames = forwardedFrames;
        this.floodedFrames = floodedFrames;
        this.droppedFrames = droppedFrames;
        this.pausedIngressEvents = pausedIngressEvents;
        this.faultDroppedFrames = faultDroppedFrames;
        this.outOfOrderEvents = outOfOrderEvents;
        this.stormDroppedFrames = stormDroppedFrames;
        this.queueFullDroppedFrames = queueFullDroppedFrames;
    }

    /** 一致快照：字段逐项拷贝。需在端口/交换机锁内调用。 */
    public PortStatistics snapshot() {
        return new PortStatistics(receivedFrames, forwardedFrames, floodedFrames, droppedFrames,
                pausedIngressEvents, faultDroppedFrames, outOfOrderEvents,
                stormDroppedFrames, queueFullDroppedFrames);
    }

    public void reset() {
        receivedFrames = 0;
        forwardedFrames = 0;
        floodedFrames = 0;
        droppedFrames = 0;
        pausedIngressEvents = 0;
        faultDroppedFrames = 0;
        outOfOrderEvents = 0;
        stormDroppedFrames = 0;
        queueFullDroppedFrames = 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PortStatistics c)) return false;
        return receivedFrames == c.receivedFrames
                && forwardedFrames == c.forwardedFrames
                && floodedFrames == c.floodedFrames
                && droppedFrames == c.droppedFrames
                && pausedIngressEvents == c.pausedIngressEvents
                && faultDroppedFrames == c.faultDroppedFrames
                && outOfOrderEvents == c.outOfOrderEvents
                && stormDroppedFrames == c.stormDroppedFrames
                && queueFullDroppedFrames == c.queueFullDroppedFrames;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(receivedFrames, forwardedFrames, floodedFrames, droppedFrames,
                pausedIngressEvents, faultDroppedFrames, outOfOrderEvents,
                stormDroppedFrames, queueFullDroppedFrames);
    }
}

