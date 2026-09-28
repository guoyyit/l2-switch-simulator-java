package com.github.guoyyit.l2switchsimulatorjava.fdb;

import com.github.guoyyit.l2switchsimulatorjava.model.MacAddress;

/**
 * 转发表条目：MAC + VLAN -> 端口，带学习时间与最近刷新时间。
 */
public final class FdbEntry {

    private final MacAddress mac;
    private final int portIndex;
    private int vlan;
    private final long learnedAtTick;
    private long lastRefreshedTick;

    public FdbEntry(MacAddress mac, int portIndex, int vlan, long learnedAtTick) {
        this.mac = mac;
        this.portIndex = portIndex;
        this.vlan = vlan;
        this.learnedAtTick = learnedAtTick;
        this.lastRefreshedTick = learnedAtTick;
    }

    public MacAddress getMac() { return mac; }
    public int getPortIndex() { return portIndex; }
    public int getVlan() { return vlan; }
    public long getLearnedAtTick() { return learnedAtTick; }
    public long getLastRefreshedTick() { return lastRefreshedTick; }

    /** 重复学习刷新计时。 */
    public void refresh(long nowTick) {
        this.lastRefreshedTick = nowTick;
    }

    public boolean isExpired(long nowTick, long agingTicks) {
        return agingTicks > 0 && nowTick - lastRefreshedTick >= agingTicks;
    }
}
