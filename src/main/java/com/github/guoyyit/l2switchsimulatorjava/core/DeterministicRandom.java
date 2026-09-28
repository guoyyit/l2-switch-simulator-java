package com.github.guoyyit.l2switchsimulatorjava.core;

/**
 * 固定种子的线性同余随机源，保证相同种子下逐次运行结果一致。
 */
public final class DeterministicRandom {

    private long state;

    public DeterministicRandom(long seed) {
        this.state = seed;
    }

    public long nextLong() {
        state = (state * 6364136223846793005L + 1442695040888963407L) & 0x7FFFFFFFFFFFFFFFL;
        return state;
    }

    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }
}
