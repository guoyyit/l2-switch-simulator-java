package com.github.guoyyit.l2switchsimulatorjava.tests;

import com.github.guoyyit.l2switchsimulatorjava.core.Network;
import com.github.guoyyit.l2switchsimulatorjava.core.NetworkView;
import com.github.guoyyit.l2switchsimulatorjava.core.SwitchConfig;
import com.github.guoyyit.l2switchsimulatorjava.fdb.FdbEntry;
import com.github.guoyyit.l2switchsimulatorjava.fdb.FdbInvalidationReason;
import com.github.guoyyit.l2switchsimulatorjava.fdb.ForwardingDatabase;
import com.github.guoyyit.l2switchsimulatorjava.model.Frame;
import com.github.guoyyit.l2switchsimulatorjava.model.MacAddress;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 需求 2：源地址学习、重复学习刷新、老化、端口禁用与转发域变化失效。
 */
class LearningAgingTest {

    private final MacAddress a = MacAddress.of("00:00:00:00:00:01");
    private final MacAddress b = MacAddress.of("00:00:00:00:00:02");

    @Test
    void unitLearningRefreshAndAging() {
        ForwardingDatabase fdb = new ForwardingDatabase(10L);
        assertTrue(fdb.learn(a, 1, 0, 0L));
        assertFalse(fdb.learn(a, 1, 0, 5L)); // 重复学习：刷新
        assertEquals(1, fdb.lookup(a, 0, 9L));   // 刷新后 9 < 5+10
        assertEquals(-1, fdb.lookup(a, 0, 15L)); // 已老化
        assertEquals(0, fdb.size());
    }

    @Test
    void agingSweepRemovesExpired() {
        ForwardingDatabase fdb = new ForwardingDatabase(3L);
        fdb.learn(a, 0, 0, 0L);
        fdb.learn(b, 1, 0, 2L);
        assertEquals(1, fdb.sweep(3L)); // a 过期
        assertEquals(1, fdb.size());
        assertEquals(1, fdb.sweep(6L)); // b 过期
        assertEquals(0, fdb.size());
    }

    @Test
    void vlanScopedEntries() {
        ForwardingDatabase fdb = new ForwardingDatabase(1000L);
        fdb.learn(a, 1, 10, 0L);
        assertEquals(-1, fdb.lookup(a, 20, 0L));
        assertEquals(1, fdb.lookup(a, 10, 0L));
    }

    @Test
    void endToEndAgingFallsBackToFlood() {
        SwitchConfig cfg = new SwitchConfig();
        cfg.agingTicks = 5L;
        Network net = new Network(7L);
        net.addSwitch("sw", 4, cfg);

        net.inject("sw", 0, new Frame(1, a, b, 0, 0, null)); // 学习 a@0，泛洪
        net.drainHosts();
        net.inject("sw", 1, new Frame(2, b, a, 0, 0, null)); // 已知单播到 0
        assertEquals(1, net.peekHostQueue("sw", 0).size());
        assertEquals(0, net.peekHostQueue("sw", 2).size());
        net.drainHosts();

        // 超过老化时间后重新查询：未知 -> 再次泛洪
        for (int i = 0; i < 6; i++) {
            net.step();
        }
        net.inject("sw", 1, new Frame(3, b, a, 0, 0, null));
        assertEquals(1, net.peekHostQueue("sw", 2).size()); // 又收到泛洪
    }

    @Test
    void disablingPortInvalidatesLearnedEntries() {
        Network net = new Network(1L);
        net.addSwitch("sw", 4, new SwitchConfig());
        net.inject("sw", 2, new Frame(1, a, b, 0, 0, null));
        NetworkView before = net.snapshot();
        assertEquals(1, before.switches().get(0).fdb().size());

        net.disablePort("sw", 2);
        assertEquals(0, net.snapshot().switches().get(0).fdb().size());
        // 禁用口收帧被丢弃且有诊断记录
        net.inject("sw", 2, new Frame(2, a, b, 0, 0, null));
        assertEquals("PORT_DISABLED", net.recentDrops().get(0).reason());
    }

    @Test
    void vlanChangeInvalidatesEntriesAndScopesFlooding() {
        Network net = new Network(1L);
        net.addSwitch("sw", 4, new SwitchConfig());
        net.inject("sw", 0, new Frame(1, a, b, 0, 0, null));
        assertEquals(1, net.getSwitch("sw").getFdb().size());

        net.changePortVlan("sw", 0, 100);
        assertEquals(0, net.getSwitch("sw").getFdb().size());

        // 不同转发域：vlan 100 的泛洪不进入 vlan 0 端口
        net.drainHosts();
        net.inject("sw", 0, new Frame(2, a, MacAddress.BROADCAST, 0, 100, null));
        assertEquals(0, net.peekHostQueue("sw", 1).size());
    }
}
