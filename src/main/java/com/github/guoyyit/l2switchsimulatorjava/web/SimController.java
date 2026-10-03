package com.github.guoyyit.l2switchsimulatorjava.web;

import com.github.guoyyit.l2switchsimulatorjava.sim.core.Simulation;
import com.github.guoyyit.l2switchsimulatorjava.sim.log.Event;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.Frame;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.FramePriority;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.PortConfig;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.PortFault;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.SimulationView;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.SwitchConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 二层交换仿真服务的 HTTP 接口。 */
@RestController
@RequestMapping("/api/sim")
public class SimController {

    private Simulation sim = new Simulation(42L);

    @PostMapping("/reset")
    public Map<String, Object> reset(@RequestParam(defaultValue = "42") long seed) {
        sim = new Simulation(seed);
        return Map.of("seed", seed);
    }

    @PostMapping("/tick")
    public SimulationView tick(@RequestParam(defaultValue = "1") int n) {
        sim.tick(n);
        return sim.snapshot();
    }

    @GetMapping("/state")
    public SimulationView state() {
        return sim.snapshot();
    }

    @PostMapping("/switches")
    public SimulationView addSwitch(@RequestBody AddSwitchRequest req) {
        long aging = req.agingTicks() == 0 ? SwitchConfig.defaults().agingTicks() : req.agingTicks();
        sim.addSwitch(req.name(), new SwitchConfig(aging, req.seed()));
        return sim.snapshot();
    }

    @PostMapping("/switches/{name}/ports")
    public SimulationView addPort(@PathVariable String name, @RequestBody(required = false) AddPortRequest req) {
        AddPortRequest r = req == null ? new AddPortRequest(true, null) : req;
        sim.addPort(name, r.hostFacing(), r.config() == null ? PortConfig.defaults() : r.config());
        return sim.snapshot();
    }

    @PostMapping("/switches/{name}/ports/{id}/enabled")
    public SimulationView setEnabled(@PathVariable String name, @PathVariable int id, @RequestBody EnabledRequest req) {
        sim.setPortEnabled(name, id, req.enabled());
        return sim.snapshot();
    }

    @PostMapping("/switches/{name}/ports/{id}/fault")
    public SimulationView setFault(@PathVariable String name, @PathVariable int id, @RequestBody FaultRequest req) {
        sim.setFault(name, id, new PortFault(req.dropRate(), req.reorder()));
        return sim.snapshot();
    }

    @PostMapping("/switches/{name}/ports/{id}/vlan")
    public SimulationView setVlan(@PathVariable String name, @PathVariable int id, @RequestBody VlanRequest req) {
        sim.setVlan(name, id, req.vlan());
        return sim.snapshot();
    }

    @PostMapping("/hosts")
    public SimulationView addHost(@RequestBody AddHostRequest req) {
        sim.addHost(req.name(), req.mac());
        return sim.snapshot();
    }

    @PostMapping("/hosts/{name}/connect")
    public SimulationView connectHost(@PathVariable String name, @RequestBody ConnectRequest req) {
        sim.connectHost(name, req.switchName(), req.portId());
        return sim.snapshot();
    }

    @PostMapping("/hosts/{name}/disconnect")
    public SimulationView disconnectHost(@PathVariable String name) {
        sim.disconnectHost(name);
        return sim.snapshot();
    }

    @PostMapping("/links")
    public SimulationView addLink(@RequestBody LinkRequest req) {
        sim.addLink(req.a().switchName(), req.a().portId(), req.b().switchName(), req.b().portId());
        return sim.snapshot();
    }

    @PostMapping("/hosts/{name}/inject")
    public SimulationView inject(@PathVariable String name, @RequestBody InjectRequest req) {
        sim.inject(name, req.dst(), req.vlan(), FramePriority.of(req.priority()), req.payload());
        return sim.snapshot();
    }

    @GetMapping("/drops")
    public List<?> drops() {
        return sim.dropEvents();
    }

    /** 按记录的事件序列回放 [from, to]；缺省回放全部。 */
    @PostMapping("/replay")
    public Map<String, Object> replay(@RequestParam(required = false) Long from,
                                      @RequestParam(required = false) Long to) {
        long f = from == null ? 0L : from;
        long latest = sim.eventLogStatus().get("latestSeq") == null
                ? -1L : ((Number) sim.eventLogStatus().get("latestSeq")).longValue();
        long t = to == null ? latest : to;
        Simulation.ReplayResult r = sim.replay(f, t);
        return Map.of("state", r.state(), "events", r.events(), "fromSeq", r.fromSeq(),
                "toSeq", r.toSeq(), "oldestSeq", r.oldestSeq(), "latestSeq", r.latestSeq(),
                "replayedEvents", r.replayedEvents());
    }

    /** 查询事件日志 [from, to]（含端点）；缺省返回全部已记录事件。 */
    @GetMapping("/events")
    public List<Event> events(@RequestParam(required = false) Long from,
                             @RequestParam(required = false) Long to) {
        long f = from == null ? 0L : from;
        long latest = ((Number) sim.eventLogStatus().get("latestSeq")).longValue();
        long t = to == null ? latest : to;
        return sim.events(f, t);
    }

    /** 事件日志状态。 */
    @GetMapping("/event-log")
    public Map<String, Object> eventLogStatus() {
        return sim.eventLogStatus();
    }

    /** 配置事件日志开关与容量。 */
    @PostMapping("/event-log")
    public Map<String, Object> configureEventLog(@RequestParam boolean enabled,
                                                @RequestParam int capacity) {
        sim.configureEventLog(enabled, capacity);
        return sim.eventLogStatus();
    }

    @GetMapping("/hosts/{name}/received")
    public List<Frame> received(@PathVariable String name) {
        return sim.hostReceived(name);
    }

    public record AddSwitchRequest(String name, long agingTicks, long seed) {
    }

    public record AddPortRequest(boolean hostFacing, PortConfig config) {
    }

    public record EnabledRequest(boolean enabled) {
    }

    public record FaultRequest(double dropRate, boolean reorder) {
    }

    public record VlanRequest(int vlan) {
    }

    public record AddHostRequest(String name, String mac) {
    }

    public record ConnectRequest(String switchName, int portId) {
    }

    public record EndpointRef(String switchName, int portId) {
    }

    public record LinkRequest(EndpointRef a, EndpointRef b) {
    }

    public record InjectRequest(String dst, int vlan, int priority, String payload) {
    }
}
