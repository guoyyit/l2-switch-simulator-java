package com.github.guoyyit.l2switchsimulatorjava.sim.log;

/**
 * 转发随机判定源（故障丢帧硬币、错序硬币）。
 *
 * <p>在线仿真使用 {@link LoggingDecisionSource}：从按 (交换机, 端口) 派生的独立
 * {@code Random} 取数，并把每次判定追加到事件日志。
 * 回放使用 {@link RecordedDecisionSource}：不再取随机数，而是按序返回日志中记录的判定值，
 * 从而保证“同一事件序列多次回放”与“回放与原始运行”逐次一致。
 */
public interface DecisionSource {

    enum Coin {
        /** 故障丢帧判定。 */
        FAULT,
        /** 相邻错序判定。 */
        REORDER
    }

    /**
     * 取一次随机判定值（[0,1)）。
     *
     * @param coin       判定类型
     * @param switchName 交换机名
     * @param portId     端口号
     * @param frameId    帧标识
     * @param tick       逻辑时间
     */
    double nextDouble(Coin coin, String switchName, int portId, long frameId, long tick);

    /** 登记交换机种子（在线实现用于派生端口随机源；回放实现为空操作）。 */
    default void registerSwitch(String name, long seed) {
        // no-op
    }
}
