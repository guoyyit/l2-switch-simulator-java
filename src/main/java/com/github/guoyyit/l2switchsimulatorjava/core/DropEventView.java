package com.github.guoyyit.l2switchsimulatorjava.core;

/**
 * 丢帧事件记录（故障诊断用）。
 */
public record DropEventView(long tick, String switchName, int portIndex,
                            long frameId, String reason) { }
