package com.github.guoyyit.l2switchsimulatorjava.sim.core;

import com.github.guoyyit.l2switchsimulatorjava.sim.model.Frame;

/**
 * 入端口暂存队列中的一帧及其“一次性”处理状态。
 *
 * <p>帧在暂停（背压）或限速（风暴）重试期间会保留在队首，下一个 tick 重新处理。
 * 为保证“同一帧只计一次接收、故障命运只判定一次”，用本包装记录：
 * <ul>
 *   <li>{@link #faultDecided}：故障丢帧硬币是否已抛过（重试不再抛）；</li>
 *   <li>{@link #faultDrop}：故障判定结果（是否丢弃），重试前后不变；</li>
 *   <li>{@link #rxCounted}：接收计数是否已计（重试不再计）。</li>
 * </ul>
 * 错序交换只移动本包装，不改变上述状态。
 */
final class IngressFrame {

    final Frame frame;
    boolean faultDecided;
    boolean faultDrop;
    boolean rxCounted;

    IngressFrame(Frame frame) {
        this.frame = frame;
    }
}
