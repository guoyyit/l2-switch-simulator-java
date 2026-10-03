package com.github.guoyyit.l2switchsimulatorjava.sim.log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * 有界事件序列日志（环形缓冲）。
 *
 * <p>运行期间持续追加影响转发结果的事件，供事后回放重建。特性：
 * <ul>
 *   <li>顺序号 {@code seq} 从 0 单调递增、全程唯一连续，不因容量淘汰而回绕；</li>
 *   <li>容量满后按确定策略淘汰最旧事件（FIFO），保留最近一段窗口，
 *       不阻塞仿真线程、不成为新的背压来源；</li>
 *   <li>可开关：关闭后 {@link #append} 直接返回，转发行为与统计不受影响；</li>
 *   <li>线程安全：追加与读取在同一把锁内，回放线程看到的日志视图一致。</li>
 * </ul>
 */
public final class EventLog {

    /** 容量上限的默认值（保留最近 10 万条事件）。 */
    public static final int DEFAULT_CAPACITY = 100_000;

    private final Object lock = new Object();
    private final Deque<Event> events = new ArrayDeque<>();
    private long nextSeq;
    private int capacity;
    private boolean enabled;

    public EventLog() {
        this(DEFAULT_CAPACITY, true);
    }

    public EventLog(int capacity, boolean enabled) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("事件日志容量必须 > 0");
        }
        this.capacity = capacity;
        this.enabled = enabled;
    }

    /** 追加一条事件并返回其顺序号；关闭时返回 -1。 */
    public long append(long tick, EventType type, Map<String, Object> data) {
        if (!enabled) {
            return -1L;
        }
        synchronized (lock) {
            long seq = nextSeq++;
            if (events.size() >= capacity) {
                events.pollFirst();
            }
            events.addLast(Event.of(seq, tick, type, data));
            return seq;
        }
    }

    /** 读取顺序号落在 [fromSeq, toSeq] 内的事件（含端点），按 seq 升序。 */
    public List<Event> eventsBetween(long fromSeq, long toSeq) {
        synchronized (lock) {
            List<Event> out = new ArrayList<>();
            for (Event e : events) {
                if (e.seq() >= fromSeq && e.seq() <= toSeq) {
                    out.add(e);
                }
            }
            return List.copyOf(out);
        }
    }

    /** 当前保留的全部事件，按 seq 升序。 */
    public List<Event> all() {
        synchronized (lock) {
            return List.copyOf(events);
        }
    }

    /** 已记录的最旧顺序号；无事件时返回 {@code latestSeq()+1}（即 0）。 */
    public long oldestSeq() {
        synchronized (lock) {
            return events.isEmpty() ? nextSeq : events.getFirst().seq();
        }
    }

    /** 已记录的最新顺序号；无事件时返回 -1。 */
    public long latestSeq() {
        synchronized (lock) {
            return nextSeq - 1L;
        }
    }

    public long nextSeq() {
        synchronized (lock) {
            return nextSeq;
        }
    }

    public int size() {
        synchronized (lock) {
            return events.size();
        }
    }

    public boolean isEnabled() {
        synchronized (lock) {
            return enabled;
        }
    }

    public int capacity() {
        synchronized (lock) {
            return capacity;
        }
    }

    /** 重配开关与容量；容量缩小时按 FIFO 淘汰最旧事件。 */
    public void configure(boolean enabled, int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("事件日志容量必须 > 0");
        }
        synchronized (lock) {
            this.enabled = enabled;
            this.capacity = capacity;
            while (events.size() > capacity) {
                events.pollFirst();
            }
        }
    }

    public void clear() {
        synchronized (lock) {
            events.clear();
            nextSeq = 0L;
        }
    }
}
