package com.github.guoyyit.l2switchsimulatorjava.fdb;

import com.github.guoyyit.l2switchsimulatorjava.model.MacAddress;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * MAC 地址转发表：按 (MAC, VLAN) 键的源地址学习、重复学习刷新、老化与确定性失效。
 * 非线程安全，由交换机在统一锁内调用。
 */
public final class ForwardingDatabase {

    private long agingTicks;
    private final Map<Key, FdbEntry> table = new LinkedHashMap<>();

    public ForwardingDatabase(long agingTicks) {
        this.agingTicks = agingTicks;
    }

    public long getAgingTicks() { return agingTicks; }
    public void setAgingTicks(long agingTicks) { this.agingTicks = agingTicks; }

    /**
     * 学习/刷新。
     * - 条目不存在：新学习，返回 true；
     * - 条目存在且端口相同：刷新计时，返回 false；
     * - 条目存在但端口不同：保留既有绑定，不覆盖（避免泛洪回程帧把主机口地址"迁移"到 fabric 口）。
     */
    public boolean learn(MacAddress mac, int portIndex, int vlan, long nowTick) {
        Key key = new Key(mac, vlan);
        FdbEntry existing = table.get(key);
        if (existing != null) {
            if (existing.getPortIndex() == portIndex) {
                existing.refresh(nowTick);
            }
            return false;
        }
        table.put(key, new FdbEntry(mac, portIndex, vlan, nowTick));
        return true;
    }

    /** 查询已知单播出端口；不存在或已过期返回 -1。 */
    public int lookup(MacAddress mac, int vlan, long nowTick) {
        Key key = new Key(mac, vlan);
        FdbEntry entry = table.get(key);
        if (entry == null) {
            return -1;
        }
        if (entry.isExpired(nowTick, agingTicks)) {
            table.remove(key);
            return -1;
        }
        return entry.getPortIndex();
    }

    /** 当前条目数（过期清理由调用方在推进时钟后通过 sweep 完成）。 */
    public int size() {
        return table.size();
    }

    /** 清理过期条目，返回移除数量。nowTick 为负表示仅做惰性清理不推进时间。 */
    public int sweep(long nowTick) {
        int removed = 0;
        var it = table.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, FdbEntry> e = it.next();
            if (nowTick >= 0 && e.getValue().isExpired(nowTick, agingTicks)) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    /** 端口禁用：该端口学到的所有条目立即失效。 */
    public void invalidatePort(int portIndex, FdbInvalidationReason reason) {
        table.values().removeIf(e -> e.getPortIndex() == portIndex);
    }

    /** 转发域变化：旧 VLAN 上的条目失效（调用方在迁移前调用）。 */
    public void invalidateVlan(int vlan, FdbInvalidationReason reason) {
        table.values().removeIf(e -> e.getVlan() == vlan);
    }

    /** 拓扑变化等场景：全表清空。 */
    public void flushAll(FdbInvalidationReason reason) {
        table.clear();
    }

    /** 条目快照列表，按学习顺序返回。 */
    public List<FdbEntry> entries() {
        return new ArrayList<>(table.values());
    }

    private record Key(MacAddress mac, int vlan) {
        private Key {
            Objects.requireNonNull(mac);
        }
    }
}
