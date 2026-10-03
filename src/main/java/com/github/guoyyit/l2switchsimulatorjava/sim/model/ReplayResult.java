package com.github.guoyyit.l2switchsimulatorjava.sim.model;

import java.util.List;

/**
 * 一次事件回放的结果。
 *
 * @param snapshot     回放执行到 {@code toSeq} 对应边界时的完整仿真快照（隔离上下文产生，
 *                     不触碰在线仿真）
 * @param events       请求范围内（fromSeq..toSeq）的事件视图，按顺序号升序
 * @param fromSeq      实际回放范围起点（含）
 * @param toSeq        实际回放范围终点（含，且为命令事件或 TickEnd 边界）
 * @param rebuildFromSeq 为到达 {@code fromSeq} 状态实际重建所用的起点（最近一次 Begin）
 * @param matched      回放期间重放出的观测事件与记录的观测事件是否逐次完全一致
 */
public record ReplayResult(SimulationView snapshot,
                           List<EventView> events,
                           long fromSeq,
                           long toSeq,
                           long rebuildFromSeq,
                           boolean matched) {
}
