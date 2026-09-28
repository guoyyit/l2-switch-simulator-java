package com.github.guoyyit.l2switchsimulatorjava.core;

import java.util.Queue;

import com.github.guoyyit.l2switchsimulatorjava.model.Frame;
import com.github.guoyyit.l2switchsimulatorjava.port.PortStatistics;
import com.github.guoyyit.l2switchsimulatorjava.port.PortStatus;

/**
 * 端口运行状态快照（队列内容为拷贝，统计为一致快照）。
 */
public record PortView(int index, String status, int vlan, int queueCapacity, int queueSize,
                       boolean fabricPort, boolean paused, long pausedUntilTick,
                       PortStatistics statistics, Queue<Frame> outputQueue) { }
