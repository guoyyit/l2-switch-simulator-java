# 二层交换转发仿真服务（L2 Switch Simulator）

一个纯内存、进程内的二层交换机仿真服务，用于验证多端口转发、地址学习与老化、
环路防护、广播风暴控制、队列背压/优先级、故障注入与确定性回放等逻辑。
不依赖任何外部网络设备。基于 Spring Boot 3 提供 HTTP 接口，核心仿真逻辑与 Web 层解耦，
可直接在单元测试中以 `Simulation` 类驱动。

## 能力对照

| # | 需求 | 实现要点 |
|---|------|----------|
| 1 | 多端口/主机、独立输入输出队列、启用/禁用/丢帧/错序 | `SwitchDevice` + `PortQueues`；每端口 8 个优先级输出队列与独立入端口暂存队列；`setPortEnabled` / `setFault(dropRate, reorder)` |
| 2 | 源地址学习与老化 | `ForwardingTable`，键为 (MAC, VLAN)；按 tick 老化，重复学习刷新计时；端口禁用/转发域变化立即失效 |
| 3 | 未知单播泛洪/已知单播/广播/组播 | 入端口不发副本，严格按 VLAN 转发域约束 |
| 4 | 环路防护与风暴控制 | `Topology` 用确定性并查集生成树阻断成环端口（主机端口永不阻断）；`StormGuard` 滑动窗口阈值，超阈值 DROP 或 LIMIT 限速 |
| 5 | 背压与优先级队列 | 每优先级队列有独立容量上限；严格优先级 + 等待老化抬升，低优先级不被饿死；队列满按 `DROP` 或 `PAUSE`（暂停入端口）处理 |
| 6 | 真实事件记录与确定性回放 | 运行期间持续记录“命令 + 内部观测”完整事件序列；回放在隔离上下文按记录真实重放并逐事件比对，而非仅用同种子重跑；故障判定每帧只做一次 |
| 7 | 状态查询与统计 | 所有读写在 `Simulation` 的同一把锁内串行；`snapshot()` 返回不可变视图，并发查询看不到部分更新；接收/发送/排队/丢弃按阶段拆分、可与队列深度和主机已收帧交叉核对 |
| 8 | 自动化测试与文档 | 59 个测试覆盖学习老化、泛洪环路、队列背压、故障/风暴、统计守恒、真实回放、并发回放查询与 HTTP 接口 |

## 事件记录与真实回放

### 记录范围

运行期间持续记录两类影响转发结果的事件，事件带全局连续顺序号 `seq`（从 1 开始）、
逻辑时间 `tick`、涉及的交换机/端口与帧标识 `frameId`：

- **命令事件（完整外部输入）**：`Begin`、`CmdSwitch`、`CmdPort`、`CmdPortEnabled`、
  `CmdPortConfig`、`CmdFault`、`CmdVlan`、`CmdHost`、`CmdHostConnect`、
  `CmdHostDisconnect`、`CmdLink`、`CmdLinkRemove`、`CmdInject`、`CmdTick`、`TickEnd`。
  仅命令序列即可完整重建一次运行。
- **观测事件（内部判定与结果）**：
  `FrameAdmitted`（真实到达入端口）、`FrameRejected`（入队前拒绝）、
  `FaultVerdict`（一次故障随机判定的“是否丢帧/是否错序”结果）、
  `FrameLearned`、`FrameFlooded`、`FrameDeferred`（风暴限速或背压暂停，帧留队首重试）、
  `FrameTx`（从出端口发出）、`FrameDelivered`（主机真实收到）、`FrameDrop`（最终丢弃）。

同一注入产生的泛洪多份副本共享同一 `frameId`；不同注入的帧 `frameId` 全局唯一。

### 回放语义

`POST /api/sim/replay`（或 `Simulation#replay()`）在一个**全新的隔离仿真上下文**中：

1. 从范围内最近一次 `Begin` 开始，按顺序重放命令事件（真实重跑学习/转发/泛洪/调度逻辑）；
2. 故障判定**不重新抽样**，而是消费记录中的 `FaultVerdict` 结果，因此“曾经不丢的帧”
   绝不会在某次重试中被改判为丢；
3. 重放产生的每个事件都与记录按 `seq` 逐字段比对，任何类型/顺序/内容不一致都判定失败。

因此回放结束时刻的**转发表、端口状态、统计快照、主机已收帧与丢弃事件明细**与原运行
结束时刻完全一致，转发顺序与丢帧结果逐次一致；同一事件序列多次回放结果完全一致。
回放不修改在线仿真、不占用真实端口队列，在线推进与查询不受影响；回放快照与在线快照
都在仿真锁内生成，并发读取看不到部分更新。

### 序号范围与边界

回放/事件查询可携带闭区间 `from`/`to`。范围终点 `to` 必须落在**确定边界**上：
命令事件或 `TickEnd`（一个 tick 完整结束）。落在某 tick 观测事件中间会被明确拒绝。
非法或越界请求返回 4xx 并带可区分的 `code`，绝不静默返回空结果：

| code | 触发条件 |
|------|----------|
| `RECORDING_DISABLED` | 事件记录处于关闭状态 |
| `LOG_TRUNCATED` | 记录达到容量上限后丢过事件，记录不完整 |
| `EMPTY_LOG` | 没有任何可回放事件 |
| `OUT_OF_RANGE` | 序号 `<1` 或超过已记录最大序号 |
| `INVALID_RANGE` | `from > to` 等非法范围 |
| `NOT_AT_BOUNDARY` | 终点落在一个 tick 的观测事件中间 |
| `VERIFICATION_FAILED` | 隔离回放重放出的事件与记录不一致（HTTP 409） |

### 开关、容量与满额策略

- 记录可通过 `POST /api/sim/reset?recordEvents=true|false&eventCapacity=<n>` 配置，
  或 `new Simulation(seed, new EventLogOptions(enabled, capacity))`；`eventCapacity=0` 表示不限。
- 达到容量上限后采用确定的 **DROP_NEW** 策略：保留旧事件、不再追加新事件，
  并累加“已丢弃新事件数”。记录是 O(1) 追加，绝不阻塞转发、不形成背压。
- 发生过截断时回放被 `LOG_TRUNCATED` 拒绝（避免用不完整记录假装重建成功）。
- 关闭记录时仅不保留事件，**转发行为与统计与开启时完全一致**。

## 统计口径与守恒

每个端口的计数按帧生命周期的互斥阶段拆分，可逐项交叉核对：

- `ingressArrivals`：**真实到达并进入入端口暂存队列**的帧。暂停（PAUSE）与风暴限速
  （LIMIT）期间帧留在队首重试，**只计一次**，不重复计入接收。
- `admissionRejected`：到达但在入队前被拒绝（端口禁用/环路阻断/入端口队列满）。
  这些帧未进入队列，不计 `ingressArrivals`。
- `rxFrames`：到达且**完成入处理**（未在入处理阶段被丢弃、未被静默过滤）的帧。
- `ingressDropped`：入处理阶段丢弃（故障 `FAULT_DROP`、风暴 `STORM_CONTROL`、
  无成员端口 `NO_MEMBER_PORTS`）。同一帧的故障判定只做一次，重试不改判，故只计一次。
- `egressDropped`：进入转发决策后的输出侧丢弃（`QUEUE_FULL` 输出队列满、
  出方向禁用/环路阻断）。
- `filtered`：目的与源同端口被静默过滤的帧。
- `txFrames`：真正从出端口发出的帧，每帧每端口只计一次；跨交换机投递时发送端只计
  `txFrames`，接收端拒绝只在接收端计一次，不重复。

恒等式（任意时刻、每个端口）：

```
ingressArrivals == ingressDepth(入端口暂存队列深度)
                   + ingressDropped + rxFrames + filtered
dropped         == admissionRejected + ingressDropped + egressDropped
```

全局层面：`Σ 主机口 txFrames == hostDelivered == Σ 主机已收帧数`；
`Σ 端口 dropped == 丢弃事件数 == dropReasons 按原因汇总`；
输出侧“已发送 + 仍在输出队列 + 输出侧丢弃”与接收帧数可对账。
混合故障注入、风暴控制（DROP/LIMIT）、队列背压（DROP/PAUSE）与环路阻断同时存在时仍成立。

## 核心模型

- **tick**：离散时间单位。每 tick 分两阶段：
  1. `processIngress`：按端口号升序处理入端口暂存队列（故障判定 → 源学习 → 转发/泛洪决策 → 风暴控制 → 背压）；
  2. `processEgress`：每个输出端口按“有效优先级”调度一帧出队，投递到下挂主机或邻居交换机入端口。
- **FDB**：`(MAC, VLAN) -> 端口`。`learnedAt`、`expiresAt` 以 tick 记录；`agingTicks=-1` 表示不老化。
- **生成树**：交换机间链路按 `(交换机名, 端口号)` 字典序排序后做并查集，
  第一条成环的边两端被阻断；增删链路后自动重新收敛，结果与调用历史无关、可重复。
- **优先级调度**：基础为严格优先级；帧每等待 `starvationTicks` 个 tick，有效优先级抬升 1 级（封顶 7），
  因此高优先级永远先服务，而低优先级在持续高优先级负载下也会被穿插调度。
- **背压语义**
  - `DROP`：输出队列满时该副本（或该单播帧）计 `QUEUE_FULL` 丢弃；
  - `PAUSE`：单播时暂停入端口并重试队首帧；泛洪采用整帧原子语义——只要任一候选输出队列满，
    本帧不产生任何副本，入端口暂停，避免恢复后重复泛洪。
- **风暴控制**：统计最近 `stormWindow` 个 tick 的 BUM（广播/组播）帧数，
  超过 `stormThreshold` 后按 `DROP` 立即丢弃或 `LIMIT` 把帧留在队首下一 tick 重试（不丢帧）。

## 快速开始

```bash
mvn spring-boot:run        # 默认 http://localhost:8080
mvn test                   # 运行全部测试
```

## HTTP 接口

所有变更接口返回变更后的完整仿真快照 `SimulationView`。

| 方法与路径 | 说明 |
|------------|------|
| `POST /api/sim/reset?seed=42&recordEvents=true&eventCapacity=0` | 重置仿真；可关事件记录、设容量上限（0=不限） |
| `POST /api/sim/tick?n=1` | 推进 n 个 tick |
| `GET  /api/sim/state` | 获取不可变全量快照 |
| `GET  /api/sim/drops` | 全部丢弃事件（含 tick、交换机、端口、原因、帧） |
| `GET  /api/sim/events?from=&to=` | 查询记录的事件序列（命令+观测），可带序号范围 |
| `POST /api/sim/replay?from=&to=` | 隔离回放，返回结束快照、范围事件与 `matched`；不带参数为全量 |
| `POST /api/sim/switches` | 建交换机 `{name, agingTicks, seed}` |
| `POST /api/sim/switches/{n}/ports` | 加端口 `{hostFacing, config}`，config 为 null 用默认 |
| `POST /api/sim/switches/{n}/ports/{id}/enabled` | `{enabled:false}` 禁用（立即清队列+失效学习） |
| `POST /api/sim/switches/{n}/ports/{id}/fault` | `{dropRate:0.3, reorder:true}` 故障注入 |
| `POST /api/sim/switches/{n}/ports/{id}/vlan` | `{vlan:2}` 改转发域（学习条目失效） |
| `POST /api/sim/hosts` | 加主机 `{name, mac}` |
| `POST /api/sim/hosts/{name}/connect` | `{switchName, portId}` 接入 |
| `POST /api/sim/hosts/{name}/disconnect` | 摘离 |
| `POST /api/sim/links` | 交换机互联 `{a:{switchName,portId}, b:{...}}`，自动生成树收敛 |
| `POST /api/sim/hosts/{name}/inject` | 注入帧 `{dst, vlan, priority(0-7), payload}` |
| `GET  /api/sim/hosts/{name}/received` | 主机已收帧列表 |

`PortConfig` 字段：`capacityPerPriority`、`starvationTicks`、`backpressure(DROP|PAUSE)`、
`stormThreshold`、`stormWindow`、`stormAction(DROP|LIMIT)`；默认值
`(8, 4, DROP, 16, 8, DROP)`。

### 端到端示例

```bash
curl -X POST 'localhost:8080/api/sim/reset?seed=42'
curl -X POST localhost:8080/api/sim/switches -H 'Content-Type: application/json' \
  -d '{"name":"sw","agingTicks":100,"seed":42}'
curl -X POST localhost:8080/api/sim/switches/sw/ports -H 'Content-Type: application/json' \
  -d '{"hostFacing":true,"config":null}'
curl -X POST localhost:8080/api/sim/switches/sw/ports -H 'Content-Type: application/json' \
  -d '{"hostFacing":true,"config":null}'
curl -X POST localhost:8080/api/sim/hosts -H 'Content-Type: application/json' \
  -d '{"name":"h1","mac":"00:00:00:00:00:01"}'
curl -X POST localhost:8080/api/sim/hosts -H 'Content-Type: application/json' \
  -d '{"name":"h2","mac":"00:00:00:00:00:02"}'
curl -X POST localhost:8080/api/sim/hosts/h1/connect -H 'Content-Type: application/json' \
  -d '{"switchName":"sw","portId":0}'
curl -X POST localhost:8080/api/sim/hosts/h2/connect -H 'Content-Type: application/json' \
  -d '{"switchName":"sw","portId":1}'
curl -X POST localhost:8080/api/sim/hosts/h1/inject -H 'Content-Type: application/json' \
  -d '{"dst":"ff:ff:ff:ff:ff:ff","vlan":1,"priority":0,"payload":"hello"}'
curl -X POST 'localhost:8080/api/sim/tick?n=2'
curl -s localhost:8080/api/sim/hosts/h2/received
# [{"src":"00:00:00:00:00:01","dst":"ff:ff:ff:ff:ff:ff","vlan":1,"priority":"P0","payload":"hello",...}]
```

### Java API 用法

```java
Simulation sim = new Simulation(42L);
sim.addSwitch("sw", new SwitchConfig(100, 42L));
sim.addPort("sw", true, PortConfig.defaults());
sim.addPort("sw", true, PortConfig.defaults());
sim.addHost("h1", "00:00:00:00:00:01");
sim.addHost("h2", "00:00:00:00:00:02");
sim.connectHost("h1", "sw", 0);
sim.connectHost("h2", "sw", 1);

sim.setFault("sw", 0, new PortFault(0.3, false));
sim.inject("h1", "ff:ff:ff:ff:ff:ff", 1, FramePriority.P0, "m");
sim.tick(2);

sim.hostReceived("h2");      // 实际收到的帧
sim.dropCount(DropReason.FAULT_DROP);
SimulationView v = sim.snapshot();   // 一致、不可变的全量状态
```

## 丢弃原因（DropReason）

`DISABLED_INGRESS` / `DISABLED_EGRESS` / `LOOP_BLOCKED_INGRESS` / `LOOP_BLOCKED_EGRESS` /
`FAULT_DROP` / `STORM_CONTROL` / `QUEUE_FULL`（输出队列满）/ `INGRESS_QUEUE_FULL`
（入端口暂存队列满）/ `NO_MEMBER_PORTS`。

计数归属约定：帧在哪个端口被拒绝，就计在该端口（跨链路投递时邻居入端口拒绝只在邻居侧计一次，
发送端只计 `txFrames`），保证“丢弃事件数 == 按原因汇总 == 端口 dropped 之和”。
入端口队列满（帧未进入暂存队列）归 `INGRESS_QUEUE_FULL`，输出队列满归 `QUEUE_FULL`。

### 回放入口（Java API）

```java
ReplayResult r = sim.replay();                 // 全量回放
r.snapshot();                                  // 与原运行结束快照 equals
r.matched();                                   // 逐事件比对是否全部一致
sim.replayRange(fromSeq, toSeq);               // 范围回放（终点须为命令或 TickEnd）
sim.events(from, to);                          // 查看事件视图
new Simulation(42L, EventLogOptions.enabled(10_000)); // 容量上限
new Simulation(42L, EventLogOptions.disabled());       // 关闭记录
```

## 测试

```
LearningAgingTest     学习、刷新、老化、禁用/VLAN 失效、按 VLAN 学习、确定性
FloodAndLoopTest      广播/组播/未知单播泛洪、VLAN 约束、入端口排除、三角形环路阻断与收敛
BackpressureTest      队列上限、DROP/PAUSE 背压、严格优先级、防饿死、每队列独立容量
FaultAndStormTest     确定性丢帧/错序、故障端口隔离、风暴 DROP/LIMIT、全链路回放一致
ConcurrentStatsTest   并发读写快照自洽、丢弃事件/原因/端口计数一致、快照不可变
EventReplayTest       真实回放与结束快照一致、多次回放一致、隔离不影响在线、事件字段、范围校验、记录开关与容量截断
RetryCountingTest     暂停/限速重试不重复计接收、故障判定冻结不改判、发送/丢弃只计一次
StatsConservationTest 混合故障+风暴+背压+环路下到达守恒、丢弃分类求和、主机收帧与发送对账
ConcurrentReplayTest  在线推进期间并发回放与并发读快照一致、无部分更新
SimApiTest            HTTP 接口端到端与错误码
SimReplayApiTest      回放/事件 HTTP 接口、可区分错误码、关闭记录与容量截断
```

## 关键类

- `sim.core.Simulation`：服务入口与全局锁、拓扑与事件汇总
- `sim.core.SwitchDevice`：逐 tick 的学习/转发/泛洪/背压引擎
- `sim.core.PortQueues`：入端口暂存队列 + 8 优先级输出队列与调度
- `sim.core.ForwardingTable`：地址学习表与老化
- `sim.core.StormGuard`：滑动窗口风暴控制
- `sim.core.Topology`：链路与确定性生成树
- `sim.core.EventLog`：有序、不丢不乱序、不背压的事件记录（容量满 DROP_NEW）
- `sim.core.ReplayEngine`：隔离上下文逐事件回放与校验
- `sim.model.*`：帧、MAC、配置与不可变视图
- `sim.model.SimEvent`：命令/观测事件的密封类型体系（含 seq、tick、端口、frameId、判定结果）
- `sim.model.EventLogOptions` / `EventView` / `ReplayResult` / `ReplayRejectedException`：
  记录配置、事件视图、回放结果与可区分拒绝原因
