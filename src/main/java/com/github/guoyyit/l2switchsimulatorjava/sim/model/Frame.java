package com.github.guoyyit.l2switchsimulatorjava.sim.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * 一帧以太网报文（不可变）。
 *
 * @param src      源 MAC
 * @param dst      目的 MAC
 * @param vlan     转发域（VLAN ID）
 * @param priority 802.1p 优先级 0..7
 * @param payload  业务载荷（仅用于测试断言）
 */
public final class Frame {

    private static final java.util.concurrent.atomic.AtomicLong ID_GEN =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * 全局唯一帧标识；同一注入产生的泛洪副本共享同一标识。
     * 正常运行时全局分配，回放时复用原运行记录的标识，保证同一帧在两次运行中标识一致。
     */
    private final long id;
    private final MacAddress src;
    private final MacAddress dst;
    private final int vlan;
    private final FramePriority priority;
    private final String payload;

    public Frame(MacAddress src, MacAddress dst, int vlan, FramePriority priority, String payload) {
        this(src, dst, vlan, priority, payload, ID_GEN.incrementAndGet());
    }

    /** 回放用：显式指定帧标识。 */
    public Frame(MacAddress src, MacAddress dst, int vlan, FramePriority priority, String payload,
                 long id) {
        this.src = Objects.requireNonNull(src, "src");
        this.dst = Objects.requireNonNull(dst, "dst");
        if (src.isGroup()) {
            throw new IllegalArgumentException("源 MAC 不能是组播/广播地址: " + src);
        }
        if (vlan < 0 || vlan > 4094) {
            throw new IllegalArgumentException("非法 VLAN: " + vlan);
        }
        this.vlan = vlan;
        this.priority = Objects.requireNonNull(priority, "priority");
        this.payload = payload == null ? "" : payload;
        this.id = id;
    }

    @JsonProperty
    public MacAddress src() {
        return src;
    }

    @JsonProperty
    public MacAddress dst() {
        return dst;
    }

    @JsonProperty
    public int vlan() {
        return vlan;
    }

    @JsonProperty
    public FramePriority priority() {
        return priority;
    }

    @JsonProperty
    public String payload() {
        return payload;
    }

    /** 全局唯一帧标识，仅用于事件记录区分不同注入（不参与值相等）。 */
    @JsonProperty
    public long id() {
        return id;
    }

    @JsonProperty("broadcast")
    public boolean isBroadcast() {
        return dst.isBroadcast();
    }

    @JsonProperty("multicast")
    public boolean isMulticast() {
        return dst.isMulticast();
    }

    /** 是否为广播/组播帧。 */
    @JsonProperty("group")
    public boolean isGroup() {
        return dst.isGroup();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Frame other)) {
            return false;
        }
        return vlan == other.vlan
                && src.equals(other.src)
                && dst.equals(other.dst)
                && priority == other.priority
                && payload.equals(other.payload);
    }

    @Override
    public int hashCode() {
        int result = src.hashCode();
        result = 31 * result + dst.hashCode();
        result = 31 * result + vlan;
        result = 31 * result + priority.hashCode();
        result = 31 * result + payload.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "Frame[" + src + "->" + dst + " vlan=" + vlan + " p=" + priority.value() + " '" + payload + "']";
    }
}
