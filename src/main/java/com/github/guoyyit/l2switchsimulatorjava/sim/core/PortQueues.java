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

    /** 入队一帧（包装为一次性处理状态）。 */
    public boolean offerIngress(Frame frame) {
        if (ingress.size() >= ingressCapacity) {
            return false;
        }
        ingress.addLast(new IngressFrame(frame));
        return true;
    }

    /** 直接把一个已包装的入队帧放回队首（重试时保留其一次性状态）。 */
    public void offerIngressFrame(IngressFrame head) {
        ingress.addFirst(head);
    }

    public IngressFrame peekIngressFrame() {
        return ingress.peekFirst();
    }

    public IngressFrame pollIngressFrame() {
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
