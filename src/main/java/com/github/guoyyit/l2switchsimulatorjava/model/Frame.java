package com.github.guoyyit.l2switchsimulatorjava.model;

/**
 * 以太网帧（仿真用，载荷为内存字节）。priority 取 0..7，vlan 为转发域标识。
 * hops 记录跨交换机转发跳数，用于 TTL 防环。
 */
public final class Frame {

    private final long id;
    private final MacAddress src;
    private final MacAddress dst;
    private final int priority;
    private int vlan;
    private final byte[] payload;
    private int hops;

    public Frame(long id, MacAddress src, MacAddress dst, int priority, int vlan, byte[] payload) {
        if (src == null || dst == null) {
            throw new IllegalArgumentException("src/dst mac required");
        }
        if (priority < 0 || priority > 7) {
            throw new IllegalArgumentException("priority must be 0..7: " + priority);
        }
        this.id = id;
        this.src = src;
        this.dst = dst;
        this.priority = priority;
        this.vlan = vlan;
        this.payload = payload == null ? new byte[0] : payload;
    }

    public long id() { return id; }
    public MacAddress src() { return src; }
    public MacAddress dst() { return dst; }
    public int priority() { return priority; }
    public int vlan() { return vlan; }
    public void setVlan(int vlan) { this.vlan = vlan; }
    public byte[] payload() { return payload; }
    public int hops() { return hops; }

    public boolean isBroadcast() { return dst.isBroadcast(); }
    public boolean isMulticast() { return dst.isMulticast(); }

    public int incrementHops() { return ++hops; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Frame f)) return false;
        return id == f.id;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }
}

