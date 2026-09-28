package com.github.guoyyit.l2switchsimulatorjava.tests;

import com.github.guoyyit.l2switchsimulatorjava.core.Network;
import com.github.guoyyit.l2switchsimulatorjava.core.PortView;
import com.github.guoyyit.l2switchsimulatorjava.core.SwitchConfig;
import com.github.guoyyit.l2switchsimulatorjava.model.Frame;
import com.github.guoyyit.l2switchsimulatorjava.model.MacAddress;
import com.github.guoyyit.l2switchsimulatorjava.storm.StormConfig;
import com.github.guoyyit.l2switchsimulatorjava.storm.StormMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 需求 4：广播风暴控制，超阈值按配置丢弃或限速。
 */
class StormControlTest {

    private MacAddress mac(int v) {
        return new MacAddress(v);
    }

    private Frame broadcast(long id, int src) {
        return new Frame(id, mac(src), MacAddress.BROADCAST, 0, 0, null);
    }

    @Test
    void dropModeSuppressesBeyondWindowThreshold() {
        SwitchConfig cfg = new SwitchConfig();
        cfg.stormConfig = new StormConfig(3, 10, StormMode.DROP);
        Network net = new Network(1L);
        net.addSwitch("sw", 3, cfg);

        // 同一 tick 内向端口 1 泛洪 6 个广播帧：前 3 通过，后 3 被风暴控制丢弃
        for (int i = 0; i < 6; i++) {
            net.inject("sw", 0, broadcast(100 + i, 0x10 + i));
        }
        PortView p1 = net.snapshot().switches().get(0).ports().get(1);
        assertEquals(3, p1.queueSize());
        assertEquals(3, p1.statistics().stormDroppedFrames);
        assertTrue(net.recentDrops().stream().anyMatch(d -> d.reason().equals("STORM_CONTROL")));

        // 跨过窗口后重新放行
        for (int i = 0; i < 11; i++) {
            net.step();
        }
        int before = p1.queueSize();
        net.inject("sw", 0, broadcast(200, 0x77));
        PortView p1b = net.snapshot().switches().get(0).ports().get(1);
        assertEquals(before + 1, p1b.queueSize(), "新窗口应再次放行");
    }

    @Test
    void policeModeRateLimits() {
        SwitchConfig cfg = new SwitchConfig();
        cfg.stormConfig = new StormConfig(2, 10, StormMode.POLICE);
        Network net = new Network(1L);
        net.addSwitch("sw", 3, cfg);

        // t=0 桶满为 2：放行 2 帧，第 3 帧被限速丢弃
        net.inject("sw", 0, broadcast(1, 1));
        net.inject("sw", 0, broadcast(2, 2));
        net.inject("sw", 0, broadcast(3, 3));
        PortView p1 = net.snapshot().switches().get(0).ports().get(1);
        assertEquals(2, p1.queueSize());
        assertEquals(1, p1.statistics().stormDroppedFrames);

        // 推进 5 个 tick：补充 2*5/10=1 个令牌，再放行 1 帧
        net.drainHosts();
        for (int i = 0; i < 5; i++) {
            net.step();
        }
        net.inject("sw", 0, broadcast(4, 4));
        net.inject("sw", 0, broadcast(5, 5));
        PortView p1b = net.snapshot().switches().get(0).ports().get(1);
        assertEquals(1, p1b.queueSize(), "令牌桶限速下只应补充约 1 个令牌");
    }
}
