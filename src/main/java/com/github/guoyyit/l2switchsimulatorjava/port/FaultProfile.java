package com.github.guoyyit.l2switchsimulatorjava.port;

/**
 * 单端口故障注入参数。dropProbability/oooProbability 取值 0..1。
 * 仅作用于所属端口，不影响其他端口。
 */
public final class FaultProfile {

    private double dropProbability;
    private double outOfOrderProbability;

    public FaultProfile() {
    }

    public FaultProfile(double dropProbability, double outOfOrderProbability) {
        this.dropProbability = dropProbability;
        this.outOfOrderProbability = outOfOrderProbability;
    }

    public double getDropProbability() { return dropProbability; }
    public void setDropProbability(double dropProbability) { this.dropProbability = dropProbability; }

    public double getOutOfOrderProbability() { return outOfOrderProbability; }
    public void setOutOfOrderProbability(double outOfOrderProbability) { this.outOfOrderProbability = outOfOrderProbability; }
}
