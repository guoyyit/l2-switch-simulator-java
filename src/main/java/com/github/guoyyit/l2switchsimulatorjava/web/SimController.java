package com.github.guoyyit.l2switchsimulatorjava.web;

import com.github.guoyyit.l2switchsimulatorjava.sim.core.Simulation;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.EventLogOptions;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.EventView;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.Frame;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.FramePriority;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.PortConfig;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.PortFault;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.ReplayResult;
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

    private final Object simLock = new Object();
    private Simulation sim = new Simulation(42L);

    /**
     * 重置仿真。
     *
     * @param seed         随机种子
     * @param recordEvents 是否开启事件记录（默认 true）；关闭时转发与统计不变
     * @param eventCapacity 事件容量上限，0=不限（默认 0）
     */
    @PostMapping("/reset")
    public Map<String, Object> reset(@RequestParam(defaultValue = "42") long seed,
                                     @RequestParam(defaultValue = "true") boolean recordEvents,
                                     @RequestParam(defaultValue = "0") long eventCapacity) {
        EventLogOptions options = recordEvents
                ? new EventLogOptions(true, eventCapacity)
                : EventLogOptions.disabled();
        synchronized (simLock) {
            sim = new Simulation(seed, options);
        }
        return Map.of("seed", seed, "recordEvents", recordEvents, "eventCapacity", eventCapacity);
    }

    @PostMapping("/tick")
    public SimulationView tick(@RequestParam(defaultValue = "1") int n) {
        synchronized (simLock) {
            sim.tick(n);
            return sim.snapshot();
        }
    }

    @GetMapping("/state")
    public SimulationView state() {
        synchronized (simLock) {
            return sim.snapshot();
        }
    }

    @PostMapping("/switches")
    public SimulationView addSwitch(@RequestBody AddSwitchRequest req) {
        long aging = req.agingTicks() == 0 ? SwitchConfig.defaults().agingTicks() : req.agingTicks();
        synchronized (simLock) {
            sim.addSwitch(req.name(), new SwitchConfig(aging, req.seed()));
            return sim.snapshot();
        }
    }

    @PostMapping("/switches/{name}/ports")
    public SimulationView addPort(@PathVariable String name, @RequestBody(required = false) AddPortRequest req) {
        AddPortRequest r = req == null ? new AddPortRequest(true, null) : req;
        synchronized (simLock) {
            sim.addPort(name, r.hostFacing(), r.config() == null ? PortConfig.defaults() : r.config());
            return sim.snapshot();
        }
    }

    @PostMapping("/switches/{name}/ports/{id}/enabled")
    public SimulationView setEnabled(@PathVariable String name, @PathVariable int id, @RequestBody EnabledRequest req) {
        synchronized (simLock) {
            sim.setPortEnabled(name, id, req.enabled());
            return sim.snapshot();
        }
    }

    @PostMapping("/switches/{name}/ports/{id}/fault")
    public SimulationView setFault(@PathVariable String name, @PathVariable int id, @RequestBody FaultRequest req) {
        synchronized (simLock) {
            sim.setFault(name, id, new PortFault(req.dropRate(), req.reorder()));
            return sim.snapshot();
        }
    }

    @PostMapping("/switches/{name}/ports/{id}/vlan")
    public SimulationView setVlan(@PathVariable String name, @PathVariable int id, @RequestBody VlanRequest req) {
        synchronized (simLock) {
            sim.setVlan(name, id, req.vlan());
            return sim.snapshot();
        }
    }

    @PostMapping("/hosts")
    public SimulationView addHost(@RequestBody AddHostRequest req) {
        synchronized (simLock) {
            sim.addHost(req.name(), req.mac());
            return sim.snapshot();
        }
    }

    @PostMapping("/hosts/{name}/connect")
    public SimulationView connect(@PathVariable String name, @RequestBody ConnectRequest req) {
        synchronized (simLock) {
            sim.connectHost(name, req.switchName(), req.portId());
            return sim.snapshot();
        }
    }

    @PostMapping("/hosts/{name}/disconnect")
    public SimulationView disconnect(@PathVariable String name) {
        synchronized (simLock) {
            sim.disconnectHost(name);
            return sim.snapshot();
        }
    }

    @PostMapping("/links")
    public SimulationView addLink(@RequestBody LinkRequest req) {
        synchronized (simLock) {
            sim.addLink(req.a().switchName(), req.a().portId(), req.b().switchName(), req.b().portId());
            return sim.snapshot();
        }
    }

    @PostMapping("/hosts/{name}/inject")
    public SimulationView inject(@PathVariable String name, @RequestBody InjectRequest req) {
        synchronized (simLock) {
            sim.inject(name, req.dst(), req.vlan(), FramePriority.of(req.priority()), req.payload());
            return sim.snapshot();
        }
    }

    @GetMapping("/drops")
    public List<?> drops() {
        synchronized (simLock) {
            return sim.dropEvents();
        }
    }

    @GetMapping("/hosts/{name}/received")
    public List<Frame> received(@PathVariable String name) {
        synchronized (simLock) {
            return sim.hostReceived(name);
        }
    }

    /** 查询记录的影响转发结果的事件，可带序号范围 from/to（闭区间）。 */
    @GetMapping("/events")
    public List<EventView> events(@RequestParam(required = false) Long from,
                                  @RequestParam(required = false) Long to) {
        synchronized (simLock) {
            return sim.events(from, to);
        }
    }

    /**
     * 真实回放：在隔离上下文按记录的事件序列重新执行并逐事件比对。
     * 不带参数为全量回放；带 from/to 为范围回放，终点必须是命令事件或 tick 边界。
     * 回放不修改在线仿真状态。
     */
    @PostMapping("/replay")
    public ReplayResult replay(@RequestParam(required = false) Long from,
                               @RequestParam(required = false) Long to) {
        synchronized (simLock) {
            return sim.replayRange(from, to);
        }
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
