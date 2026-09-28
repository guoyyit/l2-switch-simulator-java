package com.github.guoyyit.l2switchsimulatorjava.sim.core;

import com.github.guoyyit.l2switchsimulatorjava.sim.model.Frame;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.HostView;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.MacAddress;

import java.util.ArrayList;
import java.util.List;

/** 仿真主机：仅维护 MAC 与已接收帧。 */
public final class Host {
    private final String name;
    private final MacAddress mac;
    private String switchName;
    private int portId = -1;
    private final List<Frame> rx = new ArrayList<>();

    public Host(String name, MacAddress mac) {
        this.name = name;
        this.mac = mac;
    }

    public String name() {
        return name;
    }

    public MacAddress mac() {
        return mac;
    }

    public void attach(String switchName, int portId) {
        this.switchName = switchName;
        this.portId = portId;
    }

    public void detach() {
        switchName = null;
        portId = -1;
    }

    public void receive(Frame frame) {
        rx.add(frame);
    }

    public List<Frame> received() {
        return List.copyOf(rx);
    }

    public HostView snapshot() {
        return new HostView(name, mac, switchName, portId, List.copyOf(rx));
    }
}
