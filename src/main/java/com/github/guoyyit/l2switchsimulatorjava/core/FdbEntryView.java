package com.github.guoyyit.l2switchsimulatorjava.core;

/**
 * 转发表条目快照。
 */
public record FdbEntryView(String mac, int portIndex, int vlan,
                           long learnedAtTick, long lastRefreshedTick) { }
