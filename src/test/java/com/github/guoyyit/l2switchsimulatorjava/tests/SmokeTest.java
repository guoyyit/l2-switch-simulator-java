package com.github.guoyyit.l2switchsimulatorjava.tests;

import com.github.guoyyit.l2switchsimulatorjava.core.Network;
import com.github.guoyyit.l2switchsimulatorjava.core.SwitchConfig;
import com.github.guoyyit.l2switchsimulatorjava.model.Frame;
import com.github.guoyyit.l2switchsimulatorjava.model.MacAddress;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SmokeTest {

    @Test
    void learnAndKnownUnicast() {
        Network net = new Network(1L);
        SwitchConfig cfg = new SwitchConfig();
        net.addSwitch("sw", 4, cfg);
        MacAddress a = MacAddress.of("00:00:00:00:00:01");
        MacAddress b = MacAddress.of("00:00:00:00:00:02");

        Frame f1 = new Frame(1, a, b, 0, 0, null);
        net.inject("sw", 0, f1);
        // 未知单播：泛洪到 1,2,3
        assertEquals(1, net.peekHostQueue("sw", 1).size());
        assertEquals(1, net.peekHostQueue("sw", 2).size());
        assertEquals(1, net.peekHostQueue("sw", 3).size());

        Frame f2 = new Frame(2, b, a, 0, 0, null);
        net.inject("sw", 1, f2);
        // 已学到 a@0：仅端口 0 新收到一帧；2/3 保留 f1 泛洪帧不变
        assertEquals(1, net.peekHostQueue("sw", 0).size());
        assertEquals(1, net.peekHostQueue("sw", 2).size());
        assertEquals(1, net.peekHostQueue("sw", 3).size());
        // 端口 1 是 f2 入端口，不回送；f1 泛洪到 1 的帧仍在
        assertEquals(1, net.peekHostQueue("sw", 1).size());
    }
}
