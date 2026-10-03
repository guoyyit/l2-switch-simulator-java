package com.github.guoyyit.l2switchsimulatorjava.sim.log;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * 在线判定源：从按 (交换机种子, 端口) 派生的独立 {@link Random} 取数，并记录到事件日志。
 *
 * <p>每个端口的随机序列相互独立（与历史实现一致：{@code seed ^ 0x9E3779B97F4A7C15 * (portId+1)}），
 * 某端口的故障不会消耗其他端口的随机序列。
 */
public final class LoggingDecisionSource implements DecisionSource {

    private static final long GOLDEN = 0x9E3779B97F4A7C15L;

    private final EventLog eventLog;
    private final Map<String, Long> switchSeeds = new HashMap<>();
    private final Map<String, Random> randoms = new HashMap<>();

    public LoggingDecisionSource(EventLog eventLog) {
        this.eventLog = eventLog;
    }

    @Override
    public void registerSwitch(String name, long seed) {
        switchSeeds.put(name, seed);
    }

    @Override
    public double nextDouble(Coin coin, String switchName, int portId, long frameId, long tick) {
        Random rnd = randoms.computeIfAbsent(key(switchName, portId),
                k -> new Random(switchSeeds.getOrDefault(switchName, 0L) ^ (GOLDEN * (portId + 1L))));
        double value = rnd.nextDouble();
        eventLog.append(tick, coin == Coin.FAULT ? EventType.FAULT_DECISION : EventType.REORDER_DECISION,
                Map.of("switchName", switchName, "portId", portId, "frameId", frameId, "value", value));
        return value;
    }

    private static String key(String switchName, int portId) {
        return switchName + ":" + portId;
    }
}
