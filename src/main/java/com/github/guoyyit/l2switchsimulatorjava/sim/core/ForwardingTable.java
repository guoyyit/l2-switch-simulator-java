package com.github.guoyyit.l2switchsimulatorjava.sim.core;

import com.github.guoyyit.l2switchsimulatorjava.sim.model.FdbEntryView;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.MacAddress;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单台交换机的地址学习表。
 *
 * <p>键为 (MAC, VLAN)：同一 MAC 在不同转发域中独立学习。
 * 重复学习（同一端口）刷新计时；同一 MAC 移动到新端口则更新端口并刷新计时。
 * 老化时间按构造配置的 tick 数计算，agingTicks == -1 表示不老化。
 * 端口禁用或转发域变化时，相关条目立即失效。
 */
public final class ForwardingTable {

    private static final class Entry {
        final MacAddress mac;
        int portId;
        final int vlan;
        long learnedAt;
        long expiresAt;

        Entry(MacAddress mac, int portId, int vlan, long learnedAt, long expiresAt) {
            this.mac = mac;
            this.portId = portId;
            this.vlan = vlan;
            this.learnedAt = learnedAt;
            this.expiresAt = expiresAt;
        }
    }

    private record Key(long macValue, int vlan) {
    }

    private final long agingTicks;
    private final Map<Key, Entry> table = new ConcurrentHashMap<>();

    public ForwardingTable(long agingTicks) {
        this.agingTicks = agingTicks;
    }

    /**
     * 学习或刷新。
     *
     * @return 此前不存在该 (MAC,VLAN) 条目时返回 true（新增学习）
     */
    public boolean learn(MacAddress mac, int portId, int vlan, long now) {
        Key key = new Key(mac.value(), vlan);
        Entry e = table.get(key);
        if (e == null) {
            table.put(key, new Entry(mac, portId, vlan, now, deadline(now)));
            return true;
        }
        e.portId = portId;
        e.learnedAt = now;
        e.expiresAt = deadline(now);
        return false;
    }

    private long deadline(long now) {
        return agingTicks < 0 ? Long.MAX_VALUE : now + agingTicks;
    }

    public Optional<FdbEntryView> lookup(MacAddress mac, int vlan) {
        Entry e = table.get(new Key(mac.value(), vlan));
        if (e == null) {
            return Optional.empty();
        }
        return Optional.of(toView(e));
    }

    /** 老化扫描：移除到期条目，返回移除数量。 */
    public int age(long now) {
        if (agingTicks < 0) {
            return 0;
        }
        int removed = 0;
        for (Map.Entry<Key, Entry> me : table.entrySet()) {
            if (me.getValue().expiresAt <= now) {
                table.remove(me.getKey());
                removed++;
            }
        }
        return removed;
    }

    /** 端口失效（禁用/移除）：删除该端口上学到的所有条目。 */
    public void invalidatePort(int portId) {
        table.entrySet().removeIf(e -> e.getValue().portId == portId);
    }

    /** 转发域变化：删除该 VLAN 下的所有条目。 */
    public void invalidateVlan(int vlan) {
        table.entrySet().removeIf(e -> e.getValue().vlan == vlan);
    }

    public int size() {
        return table.size();
    }

    /** 不可加快照，按 (VLAN, MAC, 端口) 排序，保证输出确定。 */
    public List<FdbEntryView> snapshot() {
        List<FdbEntryView> views = new ArrayList<>(table.size());
        for (Entry e : table.values()) {
            views.add(toView(e));
        }
        views.sort(Comparator
                .comparingInt(FdbEntryView::vlan)
                .thenComparing(v -> v.mac().toString())
                .thenComparingInt(FdbEntryView::portId));
        return List.copyOf(views);
    }

    private static FdbEntryView toView(Entry e) {
        return new FdbEntryView(e.mac, e.portId, e.vlan, e.learnedAt, e.expiresAt);
    }
}
