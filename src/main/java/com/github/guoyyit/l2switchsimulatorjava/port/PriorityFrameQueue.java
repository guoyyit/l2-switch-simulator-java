package com.github.guoyyit.l2switchsimulatorjava.port;

import com.github.guoyyit.l2switchsimulatorjava.model.Frame;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 端口输出队列：8 个优先级子队列 + 总深度上限。
 *
 * 调度规则（确定性）：
 * 1. 基础为严格优先级，总是倾向选择最高优先级非空队列；
 * 2. 防饿死：若某低优先级队首帧在队列中等待达到 starvationTicks，
 *    则该帧获得一次优先出队（aging boost），保证高优先级流量不能将其永久饿死；
 * 3. 多个老化帧同时到期时，按优先级从低到高选择（等待最久的最低优先级先得到补偿）；
 * 4. 每个子队列内部 FIFO。
 */
public final class PriorityFrameQueue {

    public static final int PRIORITIES = 8;
    public static final long DEFAULT_STARVATION_TICKS = 8L;

    private final int capacity;
    private final long starvationTicks;
    private final List<ArrayDeque<QueuedFrame>> queues = new ArrayList<>(PRIORITIES);
    private int totalSize;

    public PriorityFrameQueue(int capacity) {
        this(capacity, DEFAULT_STARVATION_TICKS);
    }

    public PriorityFrameQueue(int capacity, long starvationTicks) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        this.starvationTicks = starvationTicks;
        for (int p = 0; p < PRIORITIES; p++) {
            queues.add(new ArrayDeque<>());
        }
    }

    public int getCapacity() { return capacity; }

    public int size() { return totalSize; }

    public boolean isFull() { return totalSize >= capacity; }

    public boolean isEmpty() { return totalSize == 0; }

    /** 入队。返回 false 表示队列满（由调用方按背压策略处理）。 */
    public boolean enqueue(Frame frame, long nowTicks) {
        if (frame == null || isFull()) {
            return false;
        }
        queues.get(frame.priority()).addLast(new QueuedFrame(frame, nowTicks));
        totalSize++;
        return true;
    }

    /** 出队下一帧；空队列返回 null。 */
    public Frame dequeue(long nowTicks) {
        // 1. 防饿死补偿：从最低优先级开始找已到期的队首帧。
        for (int p = 0; p < PRIORITIES; p++) {
            ArrayDeque<QueuedFrame> q = queues.get(p);
            QueuedFrame head = q.peekFirst();
            if (head != null && nowTicks - head.enqueuedAtTick >= starvationTicks) {
                totalSize--;
                return q.pollFirst().frame;
            }
        }
        // 2. 严格优先级：从最高优先级开始。
        for (int p = PRIORITIES - 1; p >= 0; p--) {
            ArrayDeque<QueuedFrame> q = queues.get(p);
            if (!q.isEmpty()) {
                totalSize--;
                return q.pollFirst().frame;
            }
        }
        return null;
    }

    /** peek 下一帧但不移除（与 dequeue 相同的调度选择，但仅查看队首）。 */
    public Frame peek(long nowTicks) {
        for (int p = 0; p < PRIORITIES; p++) {
            ArrayDeque<QueuedFrame> q = queues.get(p);
            QueuedFrame head = q.peekFirst();
            if (head != null && nowTicks - head.enqueuedAtTick >= starvationTicks) {
                return head.frame;
            }
        }
        for (int p = PRIORITIES - 1; p >= 0; p--) {
            QueuedFrame head = queues.get(p).peekFirst();
            if (head != null) {
                return head.frame;
            }
        }
        return null;
    }

    /** 队列内容快照：先低优先级后高优先级，子队列内 FIFO（与补偿后出队顺序一致）。 */
    public List<Frame> snapshotByPriority() {
        List<Frame> copy = new ArrayList<>(totalSize);
        for (int p = 0; p < PRIORITIES; p++) {
            for (QueuedFrame qf : queues.get(p)) {
                copy.add(qf.frame);
            }
        }
        return copy;
    }

    public int sizeOfPriority(int priority) {
        return queues.get(priority).size();
    }

    public void clear() {
        for (ArrayDeque<QueuedFrame> q : queues) {
            q.clear();
        }
        totalSize = 0;
    }

    private record QueuedFrame(Frame frame, long enqueuedAtTick) { }
}
