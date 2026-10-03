package com.github.guoyyit.l2switchsimulatorjava.sim.model;

/** 一次丢弃事件的不可变记录。 */
public record DropEvent(long tick, String switchName, int portId, DropReason reason, Frame frame,
                        long frameId) {

    public DropEvent(long tick, String switchName, int portId, DropReason reason, Frame frame) {
        this(tick, switchName, portId, reason, frame, frame.id());
    }
}
