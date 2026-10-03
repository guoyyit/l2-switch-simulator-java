package com.github.guoyyit.l2switchsimulatorjava.sim.model;

/**
 * 回放请求被明确拒绝。{@link #code()} 给出可区分的机器可读原因码，
 * HTTP 层映射为 4xx，绝不静默返回空结果。
 */
public final class ReplayRejectedException extends RuntimeException {

    /** 事件记录处于关闭状态，没有任何可回放内容。 */
    public static final String RECORDING_DISABLED = "RECORDING_DISABLED";
    /** 记录因达到容量上限发生过截断，记录不完整，拒绝回放。 */
    public static final String LOG_TRUNCATED = "LOG_TRUNCATED";
    /** 已保留事件范围内没有任何事件（如刚 reset 后）。 */
    public static final String EMPTY_LOG = "EMPTY_LOG";
    /** 请求的序号超出已记录范围（from/to 超过最大顺序号或小于 1）。 */
    public static final String OUT_OF_RANGE = "OUT_OF_RANGE";
    /** 范围非法（from &gt; to 等）。 */
    public static final String INVALID_RANGE = "INVALID_RANGE";
    /** 范围端点落在一个 tick 的观测事件中间，无法给出确定的边界快照。 */
    public static final String NOT_AT_BOUNDARY = "NOT_AT_BOUNDARY";
    /** 隔离回放重放出的事件与记录不一致（理论上不应发生）。 */
    public static final String VERIFICATION_FAILED = "VERIFICATION_FAILED";

    private final String code;

    public ReplayRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
