package com.github.guoyyit.l2switchsimulatorjava.api;

import com.github.guoyyit.l2switchsimulatorjava.core.Network;
import com.github.guoyyit.l2switchsimulatorjava.core.NetworkView;
import org.springframework.stereotype.Service;

/**
 * 仿真服务：持有单个内存态 {@link Network}。
 * 全部操作经 Network 内部锁串行化，并发查询只能看到完整一致的状态快照。
 */
@Service
public class SimulationService {

    private long seed = 42L;
    private Network network = new Network(42L);

    /** 以给定随机种子重置网络。 */
    public synchronized Network reset(long newSeed) {
        this.seed = newSeed;
        this.network = new Network(newSeed);
        return this.network;
    }

    public synchronized long seed() {
        return seed;
    }

    public synchronized Network network() {
        return network;
    }

    public synchronized NetworkView snapshot() {
        return network.snapshot();
    }
}
