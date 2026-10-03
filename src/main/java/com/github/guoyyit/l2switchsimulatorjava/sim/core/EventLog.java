package com.github.guoyyit.l2switchsimulatorjava.sim.core;

import com.github.guoyyit.l2switchsimulatorjava.sim.model.EventLogOptions;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.SimEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * 有序事件记录器。
 *
 * <p>所有 append 都由 {@code Simulation} 的同一把锁串行调用，因此事件严格按发生顺序
 * 追加，顺序号连续、不会乱序。append 为 O(1) 内存写入，绝不阻塞、绝不向调用方
 * （转发路径）施加背压。
 *
 * <p>容量策略是确定的 {@code DROP_NEW}：记录开启且达到容量上限后，新事件被静默
 * 跳过（不入列、不覆盖旧事件），同时 {@code droppedNew} 单调累加；仿真转发行为
 * 与统计不受影响。回放时若发现日志被截断会被明确拒绝（而不是静默回放不完整记录）。
 */
public final class EventLog {

    private EventLogOptions options;
    private final List<SimEvent> events = new ArrayList<>();
    private long nextSeq = 1L;
    private long droppedNew;

    public EventLog(EventLogOptions options) {
        this.options = options == null ? EventLogOptions.defaults() : options;
    }

    public boolean enabled() {
        return options.enabled();
    }

    public EventLogOptions options() {
        return options;
    }

    public long capacity() {
        return options.capacity();
    }

    /** 已保留事件数。 */
    public synchronized int size() {
        return events.size();
    }

    /** 因容量达到上限而未能保留的事件数。 */
    public synchronized long droppedNew() {
        return droppedNew;
    }

    /** 是否发生过容量截断（为 true 时回放会被拒绝）。 */
    public synchronized boolean truncated() {
        return droppedNew > 0;
    }

    /** 已分配的最大顺序号（无论事件是否被保留，顺序号都单调推进，保证编号连续）。 */
    public synchronized long lastSeq() {
        return nextSeq - 1;
    }

    /**
     * 生成器模式：分配顺序号并在容量允许时保留事件。
     * 关闭记录时仍正常分配顺序号空间但不保留任何事件；事件由工厂按当前顺序号构造。
     *
     * @return 分配给该事件的顺序号（即使事件因关闭/满而未保留也会返回，供在线逻辑引用）
     */
    public synchronized long record(LongSupplier tickSupplier, EventFactory factory) {
        long seq = nextSeq++;
        if (!options.enabled()) {
            return seq;
        }
        if (options.capacity() != EventLogOptions.UNLIMITED
                && (long) events.size() >= options.capacity()) {
            droppedNew++;
            return seq;
        }
        events.add(factory.create(seq, tickSupplier.getAsLong()));
        return seq;
    }

    /** 返回已保留事件的防御性快照（顺序与发生顺序一致）。 */
    public synchronized List<SimEvent> snapshot() {
        return List.copyOf(events);
    }

    public synchronized void clear() {
        events.clear();
        nextSeq = 1L;
        droppedNew = 0;
    }

    /** 重置记录选项（在线 reset 时使用）。 */
    public synchronized void reconfigure(EventLogOptions newOptions) {
        this.options = newOptions == null ? EventLogOptions.defaults() : newOptions;
        clear();
    }

    /** 用顺序号与当前逻辑时间构造事件。 */
    @FunctionalInterface
    public interface EventFactory {
        SimEvent create(long seq, long tick);
    }
}
