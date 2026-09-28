package com.github.guoyyit.l2switchsimulatorjava.sim.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;

/**
 * 48 位以太网 MAC 地址（不可变值对象）。
 * 支持形如 {@code aa:bb:cc:dd:ee:ff}、{@code AA-BB-CC-DD-EE-FF}、{@code AABBCCDDEEFF} 的输入，
 * 统一以冒号小写形式输出。
 */
public final class MacAddress {

    public static final MacAddress BROADCAST = new MacAddress(0xFFFFFFFFFFFFL);

    private final long value;

    private MacAddress(long value) {
        this.value = value & 0xFFFFFFFFFFFFL;
    }

    public MacAddress(String value) {
        this(parse(value));
    }

    public static MacAddress of(String value) {
        return new MacAddress(parse(value));
    }

    private static long parse(String input) {
        Objects.requireNonNull(input, "mac");
        String hex = input.trim().toLowerCase().replace(":", "").replace("-", "").replace(".", "");
        if (hex.length() != 12) {
            throw new IllegalArgumentException("非法 MAC 地址: " + input);
        }
        try {
            return Long.parseLong(hex, 16);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("非法 MAC 地址: " + input, e);
        }
    }

    public boolean isBroadcast() {
        return value == BROADCAST.value;
    }

    /** 组播位：第一个字节的最低位为 1；广播地址不计入组播。 */
    public boolean isMulticast() {
        return !isBroadcast() && ((value >>> 40) & 1L) == 1L;
    }

    /** 目的 MAC 是否为 BUM（广播 / 组播 / 未知单播由转发表判定，此处仅前两者）。 */
    public boolean isGroup() {
        return ((value >>> 40) & 1L) == 1L;
    }

    public long value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MacAddress other && other.value == value;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(value);
    }

    @JsonValue
    @Override
    public String toString() {
        String hex = String.format("%012x", value);
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 6; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(hex, i * 2, i * 2 + 2);
        }
        return sb.toString();
    }
}
