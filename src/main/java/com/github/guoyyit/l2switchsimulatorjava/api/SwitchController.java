package com.github.guoyyit.l2switchsimulatorjava.api;

import com.github.guoyyit.l2switchsimulatorjava.core.DropEventView;
import com.github.guoyyit.l2switchsimulatorjava.core.NetworkView;
import com.github.guoyyit.l2switchsimulatorjava.core.Replayer;
import com.github.guoyyit.l2switchsimulatorjava.core.SwitchConfig;
import com.github.guoyyit.l2switchsimulatorjava.model.Frame;
import com.github.guoyyit.l2switchsimulatorjava.model.MacAddress;
import com.github.guoyyit.l2switchsimulatorjava.port.BackpressureMode;
import com.github.guoyyit.l2switchsimulatorjava.storm.StormConfig;
import com.github.guoyyit.l2switchsimulatorjava.storm.StormMode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 二层交换仿真服务 HTTP 接口。
 */
@RestController
@RequestMapping("/api/sim")
public class SwitchController {

    private final SimulationService service;

    public SwitchController(SimulationService service) {
        this.service = service;
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of("status", "ok", "seed", service.seed());
    }

    /** 查询整网一致快照（交换机、端口、转发表、阻断端口、近期丢帧）。 */
    @GetMapping("/snapshot")
    public NetworkView snapshot() {
        return service.snapshot();
    }

    @PostMapping("/reset")
    public Map<String, Object> reset(@RequestBody(required = false) ResetRequest request) {
        long newSeed = request == null || request.seed() == null ? 42L : request.seed();
        service.reset(newSeed);
        return Map.of("status", "reset", "seed", newSeed);
    }

    @PostMapping("/switches")
    public Map<String, Object> addSwitch(@RequestBody AddSwitchRequest request) {
        SwitchConfig config = new SwitchConfig();
        if (request.agingTicks() != null) {
            config.agingTicks = request.agingTicks();
        }
        if (request.queueCapacity() != null) {
            config.defaultQueueCapacity = request.queueCapacity();
        }
        if (request.backpressure() != null) {
            config.backpressureMode = BackpressureMode.valueOf(request.backpressure());
        }
        if (request.stormThreshold() != null) {
            config.stormConfig.setThreshold(request.stormThreshold());
        }
        if (request.stormWindowTicks() != null) {
            config.stormConfig.setWindowTicks(request.stormWindowTicks());
        }
        if (request.stormMode() != null) {
            config.stormConfig.setMode(StormMode.valueOf(request.stormMode()));
        }
        service.network().addSwitch(request.name(), request.portCount(), config);
        return Map.of("status", "added", "switch", request.name());
    }

    @PostMapping("/links")
    public Map<String, Object> addLink(@RequestBody AddLinkRequest request) {
        int vlan = request.vlan() == null ? 0 : request.vlan();
        service.network().addLink(request.switchA(), request.portA(), request.switchB(), request.portB(), vlan);
        return Map.of("status", "linked");
    }

    /** 主机向某交换机端口注入一帧。 */
    @PostMapping("/inject")
    public Map<String, Object> inject(@RequestBody InjectRequest request) {
        MacAddress src = MacAddress.of(request.src());
        MacAddress dst = MacAddress.of(request.dst());
        int priority = request.priority() == null ? 0 : request.priority();
        int vlan = request.vlan() == null ? 0 : request.vlan();
        byte[] payload = request.payloadBase64() == null
                ? new byte[0] : Base64.getDecoder().decode(request.payloadBase64());
        Frame frame = new Frame(request.frameId(), src, dst, priority, vlan, payload);
        service.network().inject(request.switchName(), request.portIndex(), frame);
        return Map.of("status", "injected", "frameId", request.frameId());
    }

    /** 推进一个虚拟时钟 tick（触发老化、fabric 队列交付）。 */
    @PostMapping("/step")
    public Map<String, Object> step() {
        service.network().step();
        return Map.of("status", "stepped", "nowTick", service.network().clock().now());
    }

    /** 交付所有主机口输出队列中的帧给主机（出方向故障在此生效）。 */
    @PostMapping("/drain")
    public Map<String, Object> drain() {
        int delivered = service.network().drainHosts();
        return Map.of("status", "drained", "delivered", delivered,
                "nowTick", service.network().clock().now());
    }

    @PostMapping("/ports/enable")
    public Map<String, Object> enable(@RequestBody PortRequest request) {
        service.network().enablePort(request.switchName(), request.portIndex());
        return Map.of("status", "enabled");
    }

    @PostMapping("/ports/disable")
    public Map<String, Object> disable(@RequestBody PortRequest request) {
        service.network().disablePort(request.switchName(), request.portIndex());
        return Map.of("status", "disabled");
    }

    @PostMapping("/ports/vlan")
    public Map<String, Object> changeVlan(@RequestBody VlanRequest request) {
        service.network().changePortVlan(request.switchName(), request.portIndex(), request.vlan());
        return Map.of("status", "vlan-changed");
    }

    @PostMapping("/storm")
    public Map<String, Object> storm(@RequestBody StormRequest request) {
        StormMode mode = request.mode() == null ? StormMode.DROP : StormMode.valueOf(request.mode());
        service.network().configureStorm(request.switchName(),
                new StormConfig(request.threshold(), request.windowTicks(), mode));
        return Map.of("status", "storm-configured");
    }

    @PostMapping("/backpressure")
    public Map<String, Object> backpressure(@RequestBody BackpressureRequest request) {
        service.network().setBackpressureMode(request.switchName(),
                BackpressureMode.valueOf(request.mode()));
        return Map.of("status", "backpressure-configured");
    }

    @PostMapping("/faults")
    public Map<String, Object> faults(@RequestBody FaultRequest request) {
        double dropP = request.dropProbability() == null ? 0.0 : request.dropProbability();
        double oooP = request.outOfOrderProbability() == null ? 0.0 : request.outOfOrderProbability();
        service.network().setFault(request.switchName(), request.portIndex(), dropP, oooP);
        return Map.of("status", "fault-configured");
    }

    @GetMapping("/drops")
    public List<DropEventView> drops() {
        return service.network().recentDrops();
    }

    @PostMapping("/drops/clear")
    public Map<String, Object> clearDrops() {
        service.network().clearDropLog();
        return Map.of("status", "cleared");
    }

    /** 以相同种子与事件序列做一次性确定性回放（不影响当前在线网络）。 */
    @PostMapping("/replay")
    public NetworkView replay(@RequestBody ReplayRequest request) {
        return new Replayer().replay(request.seed(), request.topology(), request.events());
    }

    public record ResetRequest(Long seed) { }
    public record AddSwitchRequest(String name, Integer portCount, Long agingTicks, Integer queueCapacity,
                                   String backpressure, Integer stormThreshold,
                                   Long stormWindowTicks, String stormMode) { }
    public record AddLinkRequest(String switchA, Integer portA, String switchB, Integer portB, Integer vlan) { }
    public record InjectRequest(String switchName, Integer portIndex, Long frameId, String src, String dst,
                                Integer priority, Integer vlan, String payloadBase64) { }
    public record PortRequest(String switchName, Integer portIndex) { }
    public record VlanRequest(String switchName, Integer portIndex, Integer vlan) { }
    public record StormRequest(String switchName, Integer threshold, Long windowTicks, String mode) { }
    public record BackpressureRequest(String switchName, String mode) { }
    public record FaultRequest(String switchName, Integer portIndex, Double dropProbability,
                               Double outOfOrderProbability) { }
    public record ReplayRequest(long seed, Replayer.NetworkTopologyDef topology,
                                List<com.github.guoyyit.l2switchsimulatorjava.core.SimulationEvent> events) { }
}
