package com.github.guoyyit.l2switchsimulatorjava.model;

import java.util.Locale;

/**
 * 48 位以太网 MAC 地址。不可变，可作为 Map 键。
 */
public final class MacAddress implements Comparable<MacAddress> {

    public static final MacAddress BROADCAST = new MacAddress(0xFFFFFFFFFFFFL);

    private final long value;

    public MacAddress(long value) {
        this.value = value & 0xFFFFFFFFFFFFL;
    }

    /** 解析形如 AA:BB:CC:DD:EE:FF 或 AABBCCDDEEFF 的字符串。 */
    public static MacAddress of(String hex) {
        if (hex == null) {
            throw new IllegalArgumentException("mac is null");
        }
        String h = hex.trim().replace(":", "").replace("-", "").replace(".", "");
        if (h.length() != 12) {
            throw new IllegalArgumentException("invalid mac: " + hex);
        }
        return new MacAddress(Long.parseLong(h, 16));
    }

    public long value() {
        return value;
    }

    /** 组播位：首字节最低位为 1。广播也满足该位。 */
    public boolean isMulticast() {
        return (value & 0x010000000000L) != 0L;
    }

    public boolean isBroadcast() {
        return value == 0xFFFFFFFFFFFFL;
    }

    public boolean isUnicast() {
        return !isMulticast();
    }

    @Override
    public int compareTo(MacAddress other) {
        return Long.compareUnsigned(this.value, other.value);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof MacAddress other)) {
            return false;
        }
        return value == other.value;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(value);
    }

    @Override
    public String toString() {
        String h = String.format(Locale.ROOT, "%012x", value);
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 6; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(h, i * 2, i * 2 + 2);
        }
        return sb.toString();
    }
}
