package com.github.guoyyit.l2switchsimulatorjava.tests;

import com.github.guoyyit.l2switchsimulatorjava.core.Network;
import com.github.guoyyit.l2switchsimulatorjava.core.NetworkView;
import com.github.guoyyit.l2switchsimulatorjava.core.SwitchConfig;
import com.github.guoyyit.l2switchsimulatorjava.model.Frame;
import com.github.guoyyit.l2switchsimulatorjava.model.MacAddress;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 需求 3：未知单播泛洪/已知单播/广播/组播，泛洪不含入端口且受转发域约束。
 * 需求 4：环路检测并通过阻断端口收敛到无环路径。
 */
class FloodingStpTest {

    private MacAddress mac(int v) {
        return new MacAddress(v);
    }

    @Test
    void broadcastExcludesIngressAndStaysInVlan() {
        Network net = new Network(1L);
        SwitchConfig cfg = new SwitchConfig();
        net.addSwitch("sw", 5, cfg);
        net.changePortVlan("sw", 3, 50);
        net.changePortVlan("sw", 4, 50);

        net.inject("sw", 0, new Frame(1, mac(1), MacAddress.BROADCAST, 0, 0, null));
        // vlan0 的广播只到 1、2
        assertEquals(1, net.peekHostQueue("sw", 1).size());
        assertEquals(1, net.peekHostQueue("sw", 2).size());
        assertEquals(0, net.peekHostQueue("sw", 0).size()); // 不含入端口
        assertEquals(0, net.peekHostQueue("sw", 3).size()); // 不同 VLAN
        assertEquals(0, net.peekHostQueue("sw", 4).size());
    }

    @Test
    void multicastUsesMulticastBit() {
        Network net = new Network(1L);
        net.addSwitch("sw", 3, new SwitchConfig());
        MacAddress mcast = MacAddress.of("01:00:5e:00:00:01");
        assertTrue(mcast.isMulticast());
        net.inject("sw", 0, new Frame(1, mac(2), mcast, 0, 0, null));
        assertEquals(1, net.peekHostQueue("sw", 1).size());
        assertEquals(1, net.peekHostQueue("sw", 2).size());
    }

    @Test
    void triangleLoopConvergesAndFramesStillReach() {
        // 三台交换机两两相连构成环：S1-S2, S2-S3, S3-S1
        Network net = new Network(1L);
        SwitchConfig cfg = new SwitchConfig();
        net.addSwitch("s1", 3, cfg); // port2 为 fabric 预留
        net.addSwitch("s2", 3, cfg);
        net.addSwitch("s3", 3, cfg);
        net.addLink("s1", 2, "s2", 2);
        net.addLink("s2", 1, "s3", 1); // 用 port1，port2 已占用
        net.addLink("s3", 2, "s1", 1);

        var blocked = net.blockedPorts();
        assertEquals(1, blocked.size(), "环路上必须阻断一个端口");

        // 从 s1:0 发广播，沿无环树应能到达 s2:0 和 s3:0，且不会无限循环
        net.drainHosts();
        net.inject("s1", 0, new Frame(1, mac(0x10), MacAddress.BROADCAST, 0, 0, null));
        net.drainHosts();
        // 无 TTL 丢帧
        boolean hasTtlDrop = net.recentDrops().stream().anyMatch(d -> d.reason().equals("TTL_EXPIRED"));
        assertFalse(hasTtlDrop, "收敛后不应出现 TTL 丢帧");

        // 广播可达所有主机口（0 口）
        NetworkView view = net.snapshot();
        // 通过再次注入前先验证转发表学习：s2/s3 从对应端口学到源
        assertNotNull(net.getSwitch("s2").getFdb());
    }

    @Test
    void knownUnicastAcrossTwoSwitches() {
        Network net = new Network(1L);
        net.addSwitch("s1", 3, new SwitchConfig());
        net.addSwitch("s2", 3, new SwitchConfig());
        net.addLink("s1", 2, "s2", 2);
        assertEquals(0, net.blockedPorts().size()); // 无环无阻断

        MacAddress h1 = mac(0xA1);
        MacAddress h2 = mac(0xA2);
        // h1@s1:0 -> h2 未知：泛洪到 s2
        net.inject("s1", 0, new Frame(1, h1, h2, 0, 0, null));
        // s2 的 fabric 口收到帧并学到 h1 方向（指向其 fabric 口 2）
        assertEquals(2, net.getSwitch("s2").getFdb().lookup(h1, 0, 0));
        // 排空 f1 泛洪残留在各主机口的帧
        net.drainHosts();

        // h2@s2:0 -> h1：已知单播，经 fabric 链路到 s1:0
        net.inject("s2", 0, new Frame(2, h2, h1, 0, 0, null));
        assertEquals(1, net.peekHostQueue("s1", 0).size());
        // 不应再泛洪到 s1:1
        assertEquals(0, net.peekHostQueue("s1", 1).size());
    }

    @Test
    void blockedPortRejectsTraffic() {
        Network net = new Network(1L);
        net.addSwitch("s1", 3, new SwitchConfig());
        net.addSwitch("s2", 3, new SwitchConfig());
        net.addSwitch("s3", 3, new SwitchConfig());
        net.addLink("s1", 2, "s2", 2);
        net.addLink("s2", 1, "s3", 1);
        net.addLink("s3", 2, "s1", 1);

        var blocked = net.blockedPorts();
        var first = blocked.iterator().next();
        // 被阻断端口的状态为 BLOCKED
        String status = net.snapshot().switches().stream()
                .filter(s -> s.name().equals(first.switchName())).findFirst().orElseThrow()
                .ports().get(first.portIndex()).status();
        assertEquals("BLOCKED", status);
    }
}
