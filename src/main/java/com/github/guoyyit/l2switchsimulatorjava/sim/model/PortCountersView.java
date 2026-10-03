package com.github.guoyyit.l2switchsimulatorjava.sim.model;

/** 端口计数快照。 */
public record PortCountersView(long rxFrames,
                               long txFrames,
                               long learned,
                               long flooded,
                               long dropped,
                               long pausedTicks,
                               long ingressArrivals,
                               long admissionRejected,
                               long filtered,
                               long ingressDropped,
                               long egressDropped) {

    /** 兼容旧口径（不含新增明细）。 */
    public PortCountersView(long rxFrames, long txFrames, long learned, long flooded,
                            long dropped, long pausedTicks) {
        this(rxFrames, txFrames, learned, flooded, dropped, pausedTicks, 0L, 0L, 0L, 0L, 0L);
    }
}
