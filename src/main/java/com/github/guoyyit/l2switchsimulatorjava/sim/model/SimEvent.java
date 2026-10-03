package com.github.guoyyit.l2switchsimulatorjava.sim.model;

/**
 * 影响转发结果的事件。
 *
 * <p>事件分为两类：
 * <ul>
 *   <li><b>命令事件</b>：外部对仿真施加的输入（建交换机、加端口、注入帧、推进 tick 等），
 *       是重建一次运行所需的完整输入序列；</li>
 *   <li><b>观测事件</b>：命令执行期间仿真内部发生的、影响转发结果的判定与结果
 *       （帧到达、故障判定、学习、泛洪、发送、暂停、丢弃等），是重建时必须逐次一致的输出序列。</li>
 * </ul>
 *
 * <p>所有事件携带全局唯一 {@code seq}（从 1 开始单调递增）与逻辑时间 {@code tick}，
 * 以及涉及的交换机、端口与帧标识（能定位到该信息的事件）。
 * 命令事件的 {@code tick} 为命令发生前的逻辑时间；观测事件的 {@code tick} 为其发生时刻。
 */
public sealed interface SimEvent permits
        SimEvent.Begin,
        SimEvent.CmdSwitch,
        SimEvent.CmdPort,
        SimEvent.CmdPortEnabled,
        SimEvent.CmdPortConfig,
        SimEvent.CmdFault,
        SimEvent.CmdVlan,
        SimEvent.CmdHost,
        SimEvent.CmdHostConnect,
        SimEvent.CmdHostDisconnect,
        SimEvent.CmdLink,
        SimEvent.CmdLinkRemove,
        SimEvent.CmdInject,
        SimEvent.CmdTick,
        SimEvent.TickEnd,
        SimEvent.FrameAdmitted,
        SimEvent.FrameRejected,
        SimEvent.FaultVerdict,
        SimEvent.FrameLearned,
        SimEvent.FrameFlooded,
        SimEvent.FrameDeferred,
        SimEvent.FrameDelivered,
        SimEvent.FrameTx,
        SimEvent.FrameDrop {

    /** 事件全局顺序号，从 1 开始单调递增。 */
    long seq();

    /** 事件发生时的逻辑时间（tick）。 */
    long tick();

    // ------------------------------------------------------------------
    // 命令事件：完整外部输入
    // ------------------------------------------------------------------

    /** 记录起点（对应一次 {@code Simulation} 创建/重置），携带全局种子。 */
    record Begin(long seq, long tick, long seed) implements SimEvent {
    }

    record CmdSwitch(long seq, long tick, String name, long agingTicks, long seed) implements SimEvent {
    }

    record CmdPort(long seq, long tick, String switchName, int portId, boolean hostFacing,
                   PortConfig config) implements SimEvent {
    }

    record CmdPortEnabled(long seq, long tick, String switchName, int portId, boolean enabled)
            implements SimEvent {
    }

    record CmdPortConfig(long seq, long tick, String switchName, int portId, PortConfig config)
            implements SimEvent {
    }

    record CmdFault(long seq, long tick, String switchName, int portId, PortFault fault)
            implements SimEvent {
    }

    record CmdVlan(long seq, long tick, String switchName, int portId, int vlan) implements SimEvent {
    }

    record CmdHost(long seq, long tick, String name, String mac) implements SimEvent {
    }

    record CmdHostConnect(long seq, long tick, String hostName, String switchName, int portId)
            implements SimEvent {
    }

    record CmdHostDisconnect(long seq, long tick, String hostName) implements SimEvent {
    }

    record CmdLink(long seq, long tick, String switchA, int portA, String switchB, int portB)
            implements SimEvent {
    }

    record CmdLinkRemove(long seq, long tick, String switchA, int portA, String switchB, int portB)
            implements SimEvent {
    }

    /**
     * 主机注入一帧。
     *
     * @param frameId 该次注入产生的帧的全局唯一标识；泛洪产生的多份副本共享同一标识
     */
    record CmdInject(long seq, long tick, String hostName, Frame frame, long frameId)
            implements SimEvent {
    }

    /** 推进一个 tick；该事件与其 {@link TickEnd} 之间是该 tick 的全部观测事件。 */
    record CmdTick(long seq, long tick) implements SimEvent {
    }

    /**
     * 一个 tick 的推进完整结束。它是合法的回放范围边界：
     * 回放范围的起止序号必须落在命令事件或本事件上。
     */
    record TickEnd(long seq, long tick) implements SimEvent {
    }

    // ------------------------------------------------------------------
    // 观测事件：仿真内部判定与结果
    // ------------------------------------------------------------------

    /**
     * 一帧真实到达某交换机端口并被接收入端口暂存队列。
     * 每帧在每个端口至多产生一次；暂停/限速重试不再产生本事件。
     */
    record FrameAdmitted(long seq, long tick, String switchName, int portId, long frameId)
            implements SimEvent {
    }

    /** 一帧到达某端口但在入队前被拒绝（端口禁用/阻断/入队列满）。 */
    record FrameRejected(long seq, long tick, String switchName, int portId, long frameId,
                         DropReason reason) implements SimEvent {
    }

    /**
     * 一次故障注入随机判定。同一帧在暂停/限速重试前后只判定一次，
     * 回放时必须消费同一判定结果。
     *
     * @param dropDrawn    本次抽取的“是否丢帧”结果
     * @param reorderDrawn 本次抽取的“是否与后继交换”结果
     */
    record FaultVerdict(long seq, long tick, String switchName, int portId, long frameId,
                        double dropRate, boolean reorderEnabled,
                        boolean dropDrawn, boolean reorderDrawn) implements SimEvent {
    }

    /** 源地址在该端口被学习（新增条目；重复刷新不计）。 */
    record FrameLearned(long seq, long tick, String switchName, int portId, long frameId)
            implements SimEvent {
    }

    /** 帧从该端口泛洪出一份副本。 */
    record FrameFlooded(long seq, long tick, String switchName, int portId, long frameId)
            implements SimEvent {
    }

    /** 帧因风暴限速（LIMIT）或队列背压（PAUSE）留在入端口队首，等待后续 tick 重试。 */
    record FrameDeferred(long seq, long tick, String switchName, int portId, long frameId,
                         String cause) implements SimEvent {
    }

    /** 帧从出端口发出并投递到下挂主机（主机真实收到）。 */
    record FrameDelivered(long seq, long tick, String switchName, int portId, long frameId,
                          String hostName) implements SimEvent {
    }

    /** 帧从出端口发出（投递到下挂主机或邻居入端口）。每帧在每个出端口只计一次发送。 */
    record FrameTx(long seq, long tick, String switchName, int portId, long frameId,
                   boolean hostFacing) implements SimEvent {
    }

    /** 一帧的最终命运为丢弃（每个被丢弃的帧副本只产生一次）。 */
    record FrameDrop(long seq, long tick, String switchName, int portId, long frameId,
                     DropReason reason) implements SimEvent {
    }
}
