package com.github.guoyyit.l2switchsimulatorjava.sim.log;

import java.util.List;
import java.util.Map;

/**
 * 回放判定源：不再取随机数，而是按序返回事件日志中记录的判定值。
 *
 * <p>构造时把日志中的 FAULT_DECISION / REORDER_DECISION 事件按 seq 顺序摊平，
 * 回放时每次 {@link #nextDouble} 消费一条，并校验 (判定类型, 交换机, 端口, 帧标识) 与原始序列一致；
 * 一旦不一致立即抛出，避免错误地“将错就错”。
 */
public final class RecordedDecisionSource implements DecisionSource {

    private final List<Event> draws;
    private int index;

    public RecordedDecisionSource(EventLog log) {
        this.draws = log.all().stream()
                .filter(e -> e.type() == EventType.FAULT_DECISION || e.type() == EventType.REORDER_DECISION)
                .toList();
        this.index = 0;
    }

    @Override
    public double nextDouble(Coin coin, String switchName, int portId, long frameId, long tick) {
        if (index >= draws.size()) {
            throw new IllegalStateException("回放判定序列耗尽：第 " + index + " 次判定无记录");
        }
        Event e = draws.get(index);
        EventType expected = coin == Coin.FAULT ? EventType.FAULT_DECISION : EventType.REORDER_DECISION;
        Map<String, Object> d = e.data();
        if (e.type() != expected
                || !switchName.equals(d.get("switchName"))
                || !Integer.valueOf(portId).equals(d.get("portId"))
                || !Long.valueOf(frameId).equals(d.get("frameId"))) {
            throw new IllegalStateException("回放判定序列不一致 at seq=" + e.seq()
                    + " expected=(" + expected + "," + switchName + ":" + portId + ",frame#" + frameId + ")"
                    + " but was " + e.type() + " " + d);
        }
        index++;
        return (Double) d.get("value");
    }
}
