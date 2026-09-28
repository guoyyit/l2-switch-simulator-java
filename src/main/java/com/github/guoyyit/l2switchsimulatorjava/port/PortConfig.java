package com.github.guoyyit.l2switchsimulatorjava.port;

/**
 * 端口静态配置：所属转发域、队列深度、背压模式、是否为互联口。
 */
public final class PortConfig {

    private int vlan;
    private int queueCapacity;
    private BackpressureMode backpressureMode;
    private boolean fabricPort;

    public PortConfig(int vlan, int queueCapacity, BackpressureMode backpressureMode, boolean fabricPort) {
        this.vlan = vlan;
        this.queueCapacity = queueCapacity;
        this.backpressureMode = backpressureMode;
        this.fabricPort = fabricPort;
    }

    public int getVlan() { return vlan; }
    public void setVlan(int vlan) { this.vlan = vlan; }

    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }

    public BackpressureMode getBackpressureMode() { return backpressureMode; }
    public void setBackpressureMode(BackpressureMode backpressureMode) { this.backpressureMode = backpressureMode; }

    public boolean isFabricPort() { return fabricPort; }
    public void setFabricPort(boolean fabricPort) { this.fabricPort = fabricPort; }
}
