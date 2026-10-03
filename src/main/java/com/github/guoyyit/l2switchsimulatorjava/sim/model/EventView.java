package com.github.guoyyit.l2switchsimulatorjava.sim.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 事件的 JSON 友好视图：扁平 {@code Map}，带 {@code type} 标签。
 * 视图不可变；回放接口与事件查询接口统一返回该结构。
 */
public record EventView(String type, long seq, long tick, Map<String, Object> data) {

    public EventView {
        data = Map.copyOf(data);
    }

    public static EventView of(SimEvent e) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (e instanceof SimEvent.Begin x) {
            d.put("seed", x.seed());
        } else if (e instanceof SimEvent.CmdSwitch x) {
            d.put("name", x.name());
            d.put("agingTicks", x.agingTicks());
            d.put("seed", x.seed());
        } else if (e instanceof SimEvent.CmdPort x) {
            putPort(d, x.switchName(), x.portId());
            d.put("hostFacing", x.hostFacing());
            d.put("config", x.config());
        } else if (e instanceof SimEvent.CmdPortEnabled x) {
            putPort(d, x.switchName(), x.portId());
            d.put("enabled", x.enabled());
        } else if (e instanceof SimEvent.CmdPortConfig x) {
            putPort(d, x.switchName(), x.portId());
            d.put("config", x.config());
        } else if (e instanceof SimEvent.CmdFault x) {
            putPort(d, x.switchName(), x.portId());
            d.put("fault", x.fault());
        } else if (e instanceof SimEvent.CmdVlan x) {
            putPort(d, x.switchName(), x.portId());
            d.put("vlan", x.vlan());
        } else if (e instanceof SimEvent.CmdHost x) {
            d.put("hostName", x.name());
            d.put("mac", x.mac());
        } else if (e instanceof SimEvent.CmdHostConnect x) {
            d.put("hostName", x.hostName());
            putPort(d, x.switchName(), x.portId());
        } else if (e instanceof SimEvent.CmdHostDisconnect x) {
            d.put("hostName", x.hostName());
        } else if (e instanceof SimEvent.CmdLink x) {
            d.put("a", endPoint(x.switchA(), x.portA()));
            d.put("b", endPoint(x.switchB(), x.portB()));
        } else if (e instanceof SimEvent.CmdLinkRemove x) {
            d.put("a", endPoint(x.switchA(), x.portA()));
            d.put("b", endPoint(x.switchB(), x.portB()));
        } else if (e instanceof SimEvent.CmdInject x) {
            d.put("hostName", x.hostName());
            d.put("frameId", x.frameId());
            d.put("frame", x.frame());
        } else if (e instanceof SimEvent.FrameRejected x) {
            putPort(d, x.switchName(), x.portId());
            d.put("frameId", x.frameId());
            d.put("reason", x.reason().name());
        } else if (e instanceof SimEvent.FaultVerdict x) {
            putPort(d, x.switchName(), x.portId());
            d.put("frameId", x.frameId());
            d.put("dropRate", x.dropRate());
            d.put("reorderEnabled", x.reorderEnabled());
            d.put("dropDrawn", x.dropDrawn());
            d.put("reorderDrawn", x.reorderDrawn());
        } else if (e instanceof SimEvent.FrameLearned x) {
            putPort(d, x.switchName(), x.portId());
            d.put("frameId", x.frameId());
        } else if (e instanceof SimEvent.FrameFlooded x) {
            putPort(d, x.switchName(), x.portId());
            d.put("frameId", x.frameId());
        } else if (e instanceof SimEvent.FrameDeferred x) {
            putPort(d, x.switchName(), x.portId());
            d.put("frameId", x.frameId());
            d.put("cause", x.cause());
        } else if (e instanceof SimEvent.FrameDelivered x) {
            putPort(d, x.switchName(), x.portId());
            d.put("frameId", x.frameId());
            d.put("hostName", x.hostName());
        } else if (e instanceof SimEvent.FrameTx x) {
            putPort(d, x.switchName(), x.portId());
            d.put("frameId", x.frameId());
            d.put("hostFacing", x.hostFacing());
        } else if (e instanceof SimEvent.FrameDrop x) {
            putPort(d, x.switchName(), x.portId());
            d.put("frameId", x.frameId());
            d.put("reason", x.reason().name());
        } else if (e instanceof SimEvent.FrameAdmitted x) {
            putPort(d, x.switchName(), x.portId());
            d.put("frameId", x.frameId());
        }
        return new EventView(e.getClass().getSimpleName(), e.seq(), e.tick(), d);
    }

    private static void putPort(Map<String, Object> d, String switchName, int portId) {
        d.put("switchName", switchName);
        d.put("portId", portId);
    }

    private static Map<String, Object> endPoint(String switchName, int portId) {
        return Map.of("switchName", switchName, "portId", portId);
    }
}
