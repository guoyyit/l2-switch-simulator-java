package com.github.guoyyit.l2switchsimulatorjava.sim.core;

import com.github.guoyyit.l2switchsimulatorjava.sim.model.Frame;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.FramePriority;

import java.util.ArrayDeque;
import java.util.LinkedList;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 单端口输出侧的 8 个优先级队列与入端口暂存队列。
 *
 * <p>调度规则：基础为严格优先级；当 {@code starvationTicks > 0} 时，
 * 帧等待每经过一个 starvationTicks 窗口，有效优先级提升一级（封顶 7）。
 * 因此高优先级帧绝不会被低优先级帧饿死，而长期等待的低优先级帧最终也能得到调度。
 *
 * <p>线程安全由持有该端口的交换机统一加锁保证。
 */
public final class PortQueues {

    private record Entry(Frame frame, long enqueueTick) {
    }

    /**
     * 入端口暂存队列条目。
     *
     * <p>除帧外，还携带该帧在本端口“只做一次”的故障判定结果：
     * 首次真正处理（peek 到队首并需要判定）时由交换机填入，之后无论帧因
     * PAUSE 还是风暴 LIMIT 被留在队首重试多少次，都复用同一判定，绝不重新抽取。
     *
     * @param decided      是否已完成故障判定
     * @param dropDrawn    判定结果：是否丢帧
     * @param reorderDrawn 判定结果：是否与后继交换
     * @param swapExempt   该条目已参与过一次错序交换，此后不再参与
     */
    public static final class IngressFrame {
        private final Frame frame;
        private boolean decided;
        private boolean dropDrawn;
        private boolean reorderDrawn;
        private boolean swapExempt;

        public IngressFrame(Frame frame) {
            this.frame = frame;
        }

        public Frame frame() {
            return frame;
        }

        public boolean decided() {
            return decided;
        }

        public boolean dropDrawn() {
            return dropDrawn;
        }

        public boolean reorderDrawn() {
            return reorderDrawn;
        }

        public boolean swapExempt() {
            return swapExempt;
        }

        /** 填入首次判定结果；重复调用必须给出相同结果（由调用方保证只判定一次）。 */
        public void decide(boolean dropDrawn, boolean reorderDrawn) {
            this.decided = true;
            this.dropDrawn = dropDrawn;
            this.reorderDrawn = reorderDrawn;
        }

        public void markSwapExempt() {
            this.swapExempt = true;
        }
    }

    private int capacityPerPriority;
    @SuppressWarnings("unchecked")
    private final Deque<Entry>[] egress = new Deque[8];
    private final Deque<IngressFrame> ingress = new LinkedList<>();
    private int ingressCapacity;
    private boolean ingressPaused;

    public PortQueues(int capacityPerPriority) {
        if (capacityPerPriority <= 0) {
            throw new IllegalArgumentException("队列容量必须 > 0");
        }
        this.capacityPerPriority = capacityPerPriority;
        this.ingressCapacity = capacityPerPriority * 8;
        for (int p = 0; p < 8; p++) {
            egress[p] = new ArrayDeque<>();
        }
    }

    /**
     * 重配每优先级容量。缩小后超员的尾部帧由调用方负责按背压策略处理——
     * 仿真约定：运行时容量重配仅允许放大（配置缩容需先禁用再启用端口）。
     */
    public void resizeCapacity(int newCapacity) {
        if (newCapacity < capacityPerPriority) {
            throw new IllegalArgumentException("运行时不支持缩小队列容量");
        }
        this.capacityPerPriority = newCapacity;
        this.ingressCapacity = newCapacity * 8;
    }

    public int capacityPerPriority() {
        return capacityPerPriority;
    }

    /** 输出队列入队并记录入队 tick。 */
    public boolean offer(Frame frame, long now) {
        int p = frame.priority().value();
        if (egress[p].size() >= capacityPerPriority) {
            return false;
        }
        egress[p].addLast(new Entry(frame, now));
        return true;
    }

    /**
     * 选择本 tick 出队的帧。
     * 选择依据：有效优先级（等待老化提升），其次等待时长，其次原始优先级。
     */
    public Frame poll(long now, int starvationTicks) {
        int best = -1;
        long bestEffective = -1;
        long bestWait = -1;
        for (int p = 7; p >= 0; p--) {
            Entry head = egress[p].peekFirst();
            if (head == null) {
                continue;
            }
            long wait = now - head.enqueueTick();
            long effective = starvationTicks > 0
                    ? Math.min(7, p + wait / starvationTicks)
                    : p;
            if (effective > bestEffective || (effective == bestEffective && wait > bestWait)) {
                best = p;
                bestEffective = effective;
                bestWait = wait;
            }
        }
        if (best < 0) {
            return null;
        }
        return egress[best].removeFirst().frame();
    }

    public int depth(FramePriority p) {
        return egress[p.value()].size();
    }

    public int totalDepth() {
        int n = 0;
        for (Deque<Entry> q : egress) {
            n += q.size();
        }
        return n;
    }

    public List<Integer> depths() {
        List<Integer> d = new ArrayList<>(8);
        for (Deque<Entry> q : egress) {
            d.add(q.size());
        }
        return List.copyOf(d);
    }

    public boolean hasCapacity(FramePriority p) {
        return egress[p.value()].size() < capacityPerPriority;
    }

    public boolean isIngressPaused() {
        return ingressPaused;
    }

    public void setIngressPaused(boolean value) {
        ingressPaused = value;
    }

    public void clear() {
        for (Deque<Entry> q : egress) {
            q.clear();
        }
        ingress.clear();
        ingressPaused = false;
    }

    /** 跨优先级逐帧取出全部输出队列内容（确定性顺序：优先级 0..7，队首到队尾）。 */
    public Frame pollAnyEgress() {
        for (int p = 0; p < 8; p++) {
            Entry e = egress[p].pollFirst();
            if (e != null) {
                return e.frame();
            }
        }
        return null;
    }

    public boolean offerIngress(Frame frame) {
        return offerIngress(new IngressFrame(frame));
    }

    public boolean offerIngress(IngressFrame item) {
        if (ingress.size() >= ingressCapacity) {
            return false;
        }
        ingress.addLast(item);
        return true;
    }

    public IngressFrame peekIngress() {
        return ingress.peekFirst();
    }

    public IngressFrame pollIngress() {
        return ingress.pollFirst();
    }

    /**
     * 确定性相邻错序：把队首与次队首交换，并把（交换后位于队首的）原次队首取出。
     * 原队首留在新队首，后续会按正常流程处理——它已参与过一次交换，不会再被交换。
     * 返回取出的原次队首；队列不足两帧时回退为普通出队。
     */
    public IngressFrame swapAndPollFirstTwo() {
        if (ingress.size() < 2) {
            return ingress.pollFirst();
        }
        IngressFrame head = ingress.pollFirst();
        IngressFrame second = ingress.pollFirst();
        ingress.addFirst(head);   // 队列变为 [head, ...]
        return second;            // second 交由调用方立即处理
    }

    public int ingressDepth() {
        return ingress.size();
    }
}
