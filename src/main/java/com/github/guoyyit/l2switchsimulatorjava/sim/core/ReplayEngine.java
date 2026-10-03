package com.github.guoyyit.l2switchsimulatorjava.sim.core;

import com.github.guoyyit.l2switchsimulatorjava.sim.model.EventLogOptions;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.Frame;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.ReplayRejectedException;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.SimEvent;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.SimulationView;
import com.github.guoyyit.l2switchsimulatorjava.sim.model.SwitchConfig;

import java.util.List;
import java.util.Objects;

/**
 * 隔离回放引擎。
 *
 * <p>在一个关闭了事件记录的全新 {@link Simulation} 中，从 {@code rebuildFromSeq}
 * 开始逐条执行记录的命令事件，真实重跑转发逻辑。命令执行期间仿真产生的每个事件，
 * 都与记录中对应序号的事件逐字段比对；故障判定不使用随机源，而由
 * {@link RecordedFaultChance} 消费记录里的 {@link SimEvent.FaultVerdict}。
 *
 * <p>回放在单线程内顺序执行，{@link VerifyingRecorder} 与主循环共享一个消费指针：
 * 命令事件由主循环标记“下一个将被记录”，命令方法的第一条 record 与之比对，
 * 其后带出的观测事件依次被记录器消费。
 *
 * <p>任何类型、顺序、字段不一致都抛 {@link ReplayRejectedException}
 * （{@code VERIFICATION_FAILED}）。回放全程不触碰在线仿真。
 */
final class ReplayEngine {

    private final List<SimEvent> log;
    private final long rebuildFromSeq;
    private final long toSeq;
    private int consume;
    private Simulation replay;
    private VerifyingRecorder recorder;
    private RecordedFaultChance chance;

    ReplayEngine(List<SimEvent> log, long rebuildFromSeq, long fromSeq, long toSeq) {
        this.log = log;
        this.rebuildFromSeq = rebuildFromSeq;
        this.toSeq = toSeq;
    }

    SimulationView run() {
        recorder = new VerifyingRecorder();
        chance = new RecordedFaultChance();

        int start = indexOf(rebuildFromSeq);
        consume = start;
        while (consume < log.size()) {
            SimEvent e = log.get(consume);
            if (e.seq() > toSeq) {
                break;
            }
            if (e instanceof SimEvent.Begin begin) {
                replay = new Simulation(begin.seed(), EventLogOptions.disabled());
                replay.installReplayHooks(recorder, chance);
                // 校验 Begin 的 tick 与种子（种子已用于构造，这里核对逻辑时间）
                if (begin.tick() != replay.now()) {
                    throw fail("Begin 事件逻辑时间不一致", begin, null);
                }
                consume++;
                continue;
            }
            if (isObservation(e)) {
                throw fail("命令执行缺少观测事件", e, null);
            }
            // 命令事件：executeCommand 内第一条 record 比对命令自身，
            // 其后带出的观测事件由记录器顺序消费，consume 最终停在下一个命令事件。
            executeCommand(e);
        }
        return replay.snapshot();
    }

    private void executeCommand(SimEvent e) {
        if (e instanceof SimEvent.CmdSwitch x) {
            replay.addSwitch(x.name(), new SwitchConfig(x.agingTicks(), x.seed()));
        } else if (e instanceof SimEvent.CmdPort x) {
            replay.addPort(x.switchName(), x.hostFacing(), x.config());
        } else if (e instanceof SimEvent.CmdPortEnabled x) {
            replay.setPortEnabled(x.switchName(), x.portId(), x.enabled());
        } else if (e instanceof SimEvent.CmdPortConfig x) {
            replay.configurePort(x.switchName(), x.portId(), x.config());
        } else if (e instanceof SimEvent.CmdFault x) {
            replay.setFault(x.switchName(), x.portId(), x.fault());
        } else if (e instanceof SimEvent.CmdVlan x) {
            replay.setVlan(x.switchName(), x.portId(), x.vlan());
        } else if (e instanceof SimEvent.CmdHost x) {
            replay.addHost(x.name(), x.mac());
        } else if (e instanceof SimEvent.CmdHostConnect x) {
            replay.connectHost(x.hostName(), x.switchName(), x.portId());
        } else if (e instanceof SimEvent.CmdHostDisconnect x) {
            replay.disconnectHost(x.hostName());
        } else if (e instanceof SimEvent.CmdLink x) {
            replay.addLink(x.switchA(), x.portA(), x.switchB(), x.portB());
        } else if (e instanceof SimEvent.CmdLinkRemove x) {
            replay.removeLink(x.switchA(), x.portA(), x.switchB(), x.portB());
        } else if (e instanceof SimEvent.CmdInject x) {
            replay.injectReplay(x.hostName(), x.frame(), x.frameId());
        } else if (e instanceof SimEvent.CmdTick) {
            // 复用与在线完全一致的 tick()：CmdTick、观测事件、TickEnd 全部逐事件比对
            replay.tick();
        } else {
            throw fail("未知命令事件", e, null);
        }
    }

    private static boolean isObservation(SimEvent e) {
        return e instanceof SimEvent.FrameAdmitted
                || e instanceof SimEvent.FrameRejected
                || e instanceof SimEvent.FaultVerdict
                || e instanceof SimEvent.FrameLearned
                || e instanceof SimEvent.FrameFlooded
                || e instanceof SimEvent.FrameDeferred
                || e instanceof SimEvent.FrameDelivered
                || e instanceof SimEvent.FrameTx
                || e instanceof SimEvent.FrameDrop;
    }

    private int indexOf(long seq) {
        for (int i = 0; i < log.size(); i++) {
            if (log.get(i).seq() == seq) {
                return i;
            }
        }
        throw new ReplayRejectedException(ReplayRejectedException.OUT_OF_RANGE,
                "内部错误：找不到序号 " + seq);
    }

    private ReplayRejectedException fail(String msg, SimEvent expected, SimEvent actual) {
        String a = actual == null ? "<无>" : actual.getClass().getSimpleName() + "#" + actual.seq();
        String ex = expected == null ? "<无>"
                : expected.getClass().getSimpleName() + "#" + expected.seq();
        return new ReplayRejectedException(ReplayRejectedException.VERIFICATION_FAILED,
                msg + "：期望 " + ex + "，实际 " + a);
    }

    /**
     * 校验型记录器。回放命令方法每次 record 时，消费下一个期望事件并逐字段比对，
     * 顺序号复用期望值。
     */
    private final class VerifyingRecorder implements Simulation.Recorder {
        @Override
        public long record(EventLog.EventFactory factory) {
            if (consume >= log.size()) {
                throw new ReplayRejectedException(ReplayRejectedException.VERIFICATION_FAILED,
                        "回放产生了超出记录范围的多余事件");
            }
            SimEvent want = log.get(consume);
            SimEvent actual = factory.create(want.seq(), replay.now());
            verifyAgainst(want, actual);
            consume++;
            return want.seq();
        }
    }

    /**
     * 记录型故障判定源：设备需要一次判定时，下一个期望事件必须是
     * {@link SimEvent.FaultVerdict}，返回其抽取结果，不使用随机源。
     * 注意：判定事件本身由设备随后经 sink.record 比对，这里只读取结果、不推进指针。
     */
    private final class RecordedFaultChance implements SwitchDevice.FaultChance {
        @Override
        public boolean[] decide(int portId, Frame frame, double dropRate, boolean reorderEnabled) {
            if (consume >= log.size()) {
                throw new ReplayRejectedException(ReplayRejectedException.VERIFICATION_FAILED,
                        "回放需要故障判定，但记录中已无对应事件");
            }
            SimEvent want = log.get(consume);
            if (!(want instanceof SimEvent.FaultVerdict v)) {
                throw fail("期望故障判定事件但遇到其它事件", want, null);
            }
            return new boolean[]{v.dropDrawn(), v.reorderDrawn()};
        }
    }

    private void verifyAgainst(SimEvent want, SimEvent actual) {
        if (want.getClass() != actual.getClass()) {
            throw fail("事件类型不一致", want, actual);
        }
        if (want.seq() != actual.seq()) {
            throw fail("事件顺序号不一致", want, actual);
        }
        if (want.tick() != actual.tick()) {
            throw fail("事件逻辑时间不一致", want, actual);
        }
        if (!Objects.equals(want, actual)) {
            throw fail("事件内容不一致", want, actual);
        }
    }
}
