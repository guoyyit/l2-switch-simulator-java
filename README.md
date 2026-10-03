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
| 6 | 确定性回放与故障注入 | 所有随机源为按 `(交换机种子, 端口)` 派生的专属 `Random`；运行期持续记录影响转发的事件序列（顺序号、逻辑时间、交换机/端口、帧标识、故障判定与丢弃结果）；回放时按日志逐次重放判定，同事件序列多次回放与原始运行逐次一致；某端口故障不消耗其他端口随机序列 |
| 7 | 状态查询与统计 | 所有读写在 `Simulation` 的同一把锁内串行；`snapshot()` 返回不可变视图，并发查询看不到部分更新；端口计数、FDB、丢弃原因三者自洽；帧守恒（注入+泛洪净副本==送达+丢弃+队列+过滤） |
| 8 | 自动化测试与文档 | 48 个测试覆盖学习老化、泛洪环路、队列背压、故障/风暴、事件回放一致性/隔离/范围校验、重试计数一次性、混合守恒、并发回放查询与 HTTP 接口 |

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

## 事件记录与回放

运行期间，`Simulation` 持续把影响转发结果的事件追加到有界事件日志（`EventLog`）。
每条事件包含：顺序号 `seq`（从 0 单调递增、唯一连续）、逻辑时间 `tick`、事件类型、
以及载荷（帧标识、交换机/端口、故障判定值、丢弃原因等）。

事件分两类：
- **刺激事件**：建模与配置变更（建交换机/端口/主机/链路、改配置、设故障、改 VLAN、接入/摘离）、
  帧注入 `FRAME_INJECTED`、时钟推进 `TICK`。
- **决策与结果事件**：故障丢帧判定 `FAULT_DECISION`、错序判定 `REORDER_DECISION`、
  丢弃 `DROP`、主机送达 `FRAME_DELIVERED`。

### 回放原理

回放（`ReplayEngine`）在**隔离的仿真上下文**中重建一个全新 `Simulation`：
把日志中的刺激事件按序折叠重放，转发随机判定不再取随机数，而是由
`RecordedDecisionSource` 按日志中记录的判定值按序给出。因此：
- 回放结束时刻的转发表、端口状态、统计快照与丢弃事件明细与原始运行**完全一致**；
- 转发顺序与丢帧结果**逐次一致**；
- 同一事件序列多次回放结果完全一致；
- 回放**不修改**在线仿真状态、**不消耗**真实端口队列、**不阻塞**在线推进与查询。

### 重试的一次性语义

帧在暂停（背压）或限速（风暴）重试期间会保留在入端口队首，下一个 tick 重新处理。
为保证统计与真实收发一致，仿真对每个入队帧用 `IngressFrame` 记录一次性状态：
- 故障丢帧硬币**只抛一次**（`faultDecided`），重试前后命运不变；
- 接收计数**只计一次**（`rxCounted`），重试不重复；
- 发送与丢弃同样只计一次。

### 容量上限与开关

- 事件日志为环形缓冲：容量满后按**确定策略**淘汰最旧事件（FIFO），保留最近一段窗口；
- 记录**不阻塞**仿真线程，不成为新的背压来源；
- 可通过接口开关记录与配置容量；**关闭记录时转发行为与统计保持不变**（随机判定照常进行，只是不写日志）。

### 回放范围校验

回放请求可携带事件序号范围 `[fromSeq, toSeq]`，以下请求被明确拒绝并给出可区分原因：
- `toSeq > latestSeq`：超出已记录范围；
- `fromSeq < 0` 或 `toSeq < fromSeq`：范围非法；
- `fromSeq < oldestSeq` 或日志已淘汰（`oldestSeq > 0`）：范围已被日志淘汰，无法完整回放；
- 日志为空：无事件可回放。

### 统计口径与验证

任意时刻快照满足以下守恒关系（测试 `ReplayTest` 自动校验）：
- **帧守恒**：`injected + floodNetCopies == hostDelivered + dropEvents + queueDepth + filtered`
  （`floodNetCopies` 为泛洪净增副本数，`filtered` 为同端口静默过滤的帧数）；
- **丢弃一致**：`dropEvents.size() == sum(dropReasonCounts) == sum(port.dropped)`；
- **收发一致**：主机接入端口的 `txFrames ==` 该主机 `rxFrames.size()`；
- **接收真实**：每个端口的 `rxFrames` 只统计真实到达该端口的帧，重试不重复。

## 快速开始

```bash
mvn spring-boot:run        # 默认 http://localhost:8080
mvn test                   # 运行全部测试
```

## HTTP 接口

所有变更接口返回变更后的完整仿真快照 `SimulationView`。

| 方法与路径 | 说明 |
|------------|------|
| `POST /api/sim/reset?seed=42` | 重置仿真并固定随机种子 |
| `POST /api/sim/tick?n=1` | 推进 n 个 tick |
| `GET  /api/sim/state` | 获取不可变全量快照 |
| `GET  /api/sim/drops` | 全部丢弃事件（含 tick、交换机、端口、原因、帧） |
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
| `POST /api/sim/replay?from=&to=` | 按记录的事件序列回放 `[from,to]`，缺省回放全部；返回结束状态、事件列表与日志窗口元数据 |
| `GET  /api/sim/events?from=&to=` | 查询事件日志 `[from,to]`（含端点），缺省返回全部已记录事件 |
| `GET  /api/sim/event-log` | 事件日志状态（开关、容量、条数、已记录窗口） |
| `POST /api/sim/event-log?enabled=&capacity=` | 配置事件日志开关与容量（容量缩小时按 FIFO 淘汰） |

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
`FAULT_DROP` / `STORM_CONTROL` / `QUEUE_FULL` / `NO_MEMBER_PORTS`。

计数归属约定：帧在哪个端口被拒绝，就计在该端口（跨链路投递时邻居入端口拒绝只在邻居侧计一次，
发送端只计 `txFrames`），保证“丢弃事件数 == 按原因汇总 == 端口 dropped 之和”。

## 测试

```
LearningAgingTest     学习、刷新、老化、禁用/VLAN 失效、按 VLAN 学习、确定性
FloodAndLoopTest      广播/组播/未知单播泛洪、VLAN 约束、入端口排除、三角形环路阻断与收敛
BackpressureTest      队列上限、DROP/PAUSE 背压、严格优先级、防饿死、每队列独立容量
FaultAndStormTest     确定性丢帧/错序、故障端口隔离、风暴 DROP/LIMIT、全链路回放一致
ReplayTest            事件回放一致性/隔离/确定性、序号范围校验、淘汰拒绝、暂停与限速重试计数一次性、重试不改判、混合故障守恒、并发回放与在线查询
ConcurrentStatsTest   并发读写快照自洽、丢弃事件/原因/端口计数一致、快照不可变
SimApiTest            HTTP 接口端到端与错误码
```

## 关键类

- `sim.core.Simulation`：服务入口与全局锁、拓扑与事件汇总
- `sim.core.SwitchDevice`：逐 tick 的学习/转发/泛洪/背压引擎
- `sim.core.PortQueues`：入端口暂存队列 + 8 优先级输出队列与调度
- `sim.core.ForwardingTable`：地址学习表与老化
- `sim.core.StormGuard`：滑动窗口风暴控制
- `sim.core.Topology`：链路与确定性生成树
- `sim.log.EventLog` / `Event` / `EventType`：有界事件序列日志
- `sim.log.DecisionSource` / `LoggingDecisionSource` / `RecordedDecisionSource`：转发随机判定源（在线取数+记录 / 回放按日志取数）
- `sim.core.ReplayEngine`：把刺激事件折叠到隔离仿真的回放引擎
- `sim.model.*`：帧、MAC、配置与不可变视图
