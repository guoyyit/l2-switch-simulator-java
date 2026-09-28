package com.github.guoyyit.l2switchsimulatorjava.core;

import java.util.List;

/**
 * 确定性回放器：以相同种子、拓扑定义与事件序列重建网络并逐事件重放。
 *
 * INJECT 事件：在指定 tick 之前补齐时钟，再注入；
 * TICK 事件：推进一个 tick；
 * DRAIN 事件：将所有主机口队列交付给主机（出方向故障按确定随机流生效）。
 * 不依赖墙钟时间，相同输入的转发顺序、丢弃结果与最终快照逐次一致。
 */
public final class Replayer {

    public NetworkView replay(long seed, NetworkTopologyDef topologyDef, List<SimulationEvent> events) {
        Network network = new Network(seed);
        for (SwitchDef sw : topologyDef.switches()) {
            network.addSwitch(sw.name(), sw.portCount(), new SwitchConfig());
        }
        for (LinkDef link : topologyDef.links()) {
            network.addLink(link.switchA(), link.portA(), link.switchB(), link.portB());
        }
        for (SimulationEvent event : events) {
            advanceTo(network, event.atTick());
            switch (event.type()) {
                case INJECT -> network.inject(event.switchName(), event.portIndex(), event.frame());
                case TICK -> network.step();
                case DRAIN -> network.drainHosts();
            }
        }
        return network.snapshot();
    }

    private void advanceTo(Network network, long targetTick) {
        while (network.clock().now() < targetTick) {
            network.step();
        }
    }

    /** 拓扑定义（交换机、端口数、链路）。 */
    public record NetworkTopologyDef(List<SwitchDef> switches, List<LinkDef> links) { }
    public record SwitchDef(String name, int portCount) { }
    public record LinkDef(String switchA, int portA, String switchB, int portB) { }
}
