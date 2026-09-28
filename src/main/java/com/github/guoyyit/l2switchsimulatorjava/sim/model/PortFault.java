package com.github.guoyyit.l2switchsimulatorjava.sim.model;

/**
 * 端口故障注入配置。
 *
 * @param dropRate      随机丢帧率 [0,1]
 * @param reorderEnabled 是否启用相邻帧错序（按确定性随机源）
 */
public record PortFault(double dropRate, boolean reorderEnabled) {
    public PortFault {
        if (dropRate < 0.0 || dropRate > 1.0) {
            throw new IllegalArgumentException("丢帧率必须在 [0,1]: " + dropRate);
        }
    }

    public static PortFault none() {
        return new PortFault(0.0, false);
    }
}
