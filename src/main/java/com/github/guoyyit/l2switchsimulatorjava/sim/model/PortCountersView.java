package com.github.guoyyit.l2switchsimulatorjava.sim.model;

/** 端口计数快照。 */
public record PortCountersView(long rxFrames,
                               long txFrames,
                               long learned,
                               long flooded,
                               long dropped,
                               long pausedTicks) {
}
