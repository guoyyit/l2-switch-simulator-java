package com.github.guoyyit.l2switchsimulatorjava.storm;

/**
 * 风暴超阈值后的处理策略：丢弃或限速（令牌桶）。
 */
public enum StormMode {
    DROP,
    POLICE
}
