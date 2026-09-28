# L2 Switch Simulator（本地二层交换转发仿真服务）

一个**完全进程内、无外部网络设备依赖**的二层交换机仿真服务，用于验证多端口转发、
源地址学习/老化、环路防护、广播风暴控制、优先级队列背压、确定性回放与故障诊断。

- Java 17 + Spring Boot 3.4，内存态状态 + 进程内队列
- 纯虚拟时钟（tick）驱动，不依赖墙钟时间
- 固定种子线性同余随机源，相同种子 + 相同事件序列 ⇒ 转发顺序与丢弃结果逐次一致

## 构建与运行

```bash
mvn spring-boot:run          # 启动服务，默认端口 8080
mvn test                     # 运行全部自动化测试（28 个用例）
```

健康检查：

```bash
curl http://localhost:8080/api/sim/ping
# {"status":"ok","seed":42}
```

## 核心模型

| 组件 | 说明 |
| --- | --- |
| `MacAddress` | 48 位 MAC，区分单播/组播（首字节最低位）/广播 |
| `Frame` | 帧：源/目的 MAC、优先级 0..7、VLAN（转发域）、载荷、跳数 TTL |
| `Port` | 逻辑端口：独立输出队列、启用/禁用/阻断、暂停、故障注入、统计 |
| `PriorityFrameQueue` | 8 优先级子队列 + 总深度上限；严格优先级 + 防饿死补偿 |
| `ForwardingDatabase` | 按 (MAC, VLAN) 学习，重复学习刷新，老化/端口禁用/VLAN 变化/拓扑变化失效 |
| `StormController` | 每端口固定窗口 DROP 或令牌桶 POLICE |
| `StpEngine` | 根桥 + BFS 生成树，非树边确定性阻断一端 |
| `Network` | 多交换机、链路、虚拟时钟、fabric 投递、事件日志、统一锁 |
| `Replayer` | 按拓扑定义 + 事件序列做确定性回放 |

### 转发语义

1. **源地址学习**：入站帧的源 MAC（仅单播）学习到入端口；重复学习刷新计时。
2. **已知单播**：仅转发到 FDB 命中端口（若命中入端口则静默过滤）。
3. **未知单播 / 广播 / 组播**：在**同 VLAN、管理启用、未阻断、排除入端口**的范围内泛洪，
   并受每端口风暴控制器约束。
4. **跨交换机**：fabric（trunk）口出队帧立即经链路投递到对端 fabric 口，直到本 tick 稳定；
   帧带跳数 TTL，超过 `maxFrameHops` 丢弃（防环兜底）。
5. **环路防护**：`addLink` 后自动重算生成树，阻断集合变化时各交换机转发表全量失效。
6. **背压**：输出队列满时按端口策略 `DROP`（直接丢帧）或 `PAUSE`（暂停入端口若干 tick）。
7. **防饿死**：低优先级队首等待超过阈值（默认 8 tick）即获得一次优先出队，
   保证持续高优先级流量不能将其永久饿死。
8. **故障注入**：逐端口配置入/出方向丢帧概率与出方向错序概率；
   概率 0/1 不消耗随机数，故障只影响本端口，其余端口行为不变。
9. **一致性**：`Network` 全部公共方法在同一把锁内串行，
   快照（端口队列拷贝 + 统计拷贝 + FDB 视图）不会读到处理过程中的部分更新。

## HTTP 接口

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| GET  | `/api/sim/ping` | 健康检查 |
| GET  | `/api/sim/snapshot` | 整网一致快照（交换机/端口/队列/统计/FDB/阻断端口/近期丢帧） |
| POST | `/api/sim/reset` | `{"seed":42}` 重置网络与随机种子 |
| POST | `/api/sim/switches` | 新增交换机（端口数、老化、队列、背压、风暴配置） |
| POST | `/api/sim/links` | 新增互联链路（自动 STP 收敛） |
| POST | `/api/sim/inject` | 主机向端口注入一帧并立即处理 |
| POST | `/api/sim/step` | 推进一个虚拟 tick（老化 + 限速补充 + fabric 交付） |
| POST | `/api/sim/drain` | 交付所有主机口输出队列（出方向故障在此生效） |
| POST | `/api/sim/ports/enable` `/disable` `/vlan` | 端口控制与转发域变更 |
| POST | `/api/sim/storm` | 配置风暴控制 `{threshold,windowTicks,mode}` |
| POST | `/api/sim/backpressure` | 配置背压模式 `DROP`/`PAUSE` |
| POST | `/api/sim/faults` | 配置端口丢帧/错序概率 |
| GET  | `/api/sim/drops` | 近期丢帧诊断事件（tick、交换机、端口、帧 ID、原因） |
| POST | `/api/sim/drops/clear` | 清空丢帧日志 |
| POST | `/api/sim/replay` | 按种子 + 拓扑 + 事件序列做一次性确定性回放 |

### 端到端示例：两台交换机已知单播

```bash
curl -X POST localhost:8080/api/sim/reset -H 'Content-Type: application/json' -d '{"seed":1}'
curl -X POST localhost:8080/api/sim/switches -H 'Content-Type: application/json' \
  -d '{"name":"s1","portCount":3}'
curl -X POST localhost:8080/api/sim/switches -H 'Content-Type: application/json' \
  -d '{"name":"s2","portCount":3}'
curl -X POST localhost:8080/api/sim/links -H 'Content-Type: application/json' \
  -d '{"switchA":"s1","portA":2,"switchB":"s2","portB":2}'

# h1(..0a) @ s1:0 -> h2(..0b)，未知单播泛洪（同时让两台交换机学习源方向）
curl -X POST localhost:8080/api/sim/inject -H 'Content-Type: application/json' \
  -d '{"switchName":"s1","portIndex":0,"frameId":1,
       "src":"00:00:00:00:00:0a","dst":"00:00:00:00:00:0b"}'
# h2 -> h1：已知单播，仅经 fabric 链路送达 s1:0
curl -X POST localhost:8080/api/sim/inject -H 'Content-Type: application/json' \
  -d '{"switchName":"s2","portIndex":0,"frameId":2,
       "src":"00:00:00:00:00:0b","dst":"00:00:00:00:00:0a"}'
curl localhost:8080/api/sim/snapshot
```

### 环路收敛示例（三台交换机成环）

```bash
# s1-s2, s2-s3, s3-s1 构成三角形后，STP 确定性阻断一个端口
curl -X POST localhost:8080/api/sim/links -H 'Content-Type: application/json' \
  -d '{"switchA":"s1","portA":2,"switchB":"s2","portB":2}'
curl -X POST localhost:8080/api/sim/links -H 'Content-Type: application/json' \
  -d '{"switchA":"s2","portA":1,"switchB":"s3","portB":1}'
curl -X POST localhost:8080/api/sim/links -H 'Content-Type: application/json' \
  -d '{"switchA":"s3","portA":2,"switchB":"s1","portB":1}'
# 快照中 blockedPorts 恰有一个元素，广播帧不会触发 TTL_EXPIRED
```

## 丢帧原因（故障诊断）

`PORT_DISABLED`、`PORT_BLOCKED`、`PORT_PAUSED`、`VLAN_MISMATCH`、
`FAULT_INJECTION`、`QUEUE_FULL`、`STORM_CONTROL`、`TTL_EXPIRED`、`NO_OUTPUT_PORTS`。

端口统计字段：`receivedFrames / forwardedFrames / floodedFrames / droppedFrames /
pausedIngressEvents / faultDroppedFrames / outOfOrderEvents /
stormDroppedFrames / queueFullDroppedFrames`，其中 `droppedFrames` 为各类丢弃之和。

## 测试

```
src/test/java/.../tests/
├── LearningAgingTest       地址学习、重复刷新、老化、端口禁用/VLAN 变更失效
├── FloodingStpTest         广播/组播/未知单播泛洪、VLAN 约束、三角环 STP 收敛、跨机已知单播
├── StormControlTest        固定窗口丢弃与令牌桶限速
├── BackpressureQueueTest   队列上限、DROP/PAUSE 背压、严格优先级与防饿死
├── FaultDeterminismTest    固定种子可复现、故障端口隔离、错序、Replayer 回放
├── ConcurrencyStatsTest    并发快照不撕裂、统计计数闭合
└── RestApiIntegrationTest  HTTP 端到端工作流
```

## 设计取舍

- **虚拟时钟而非真实线程调度**：所有时间量（老化、风暴窗口、暂停、防饿死阈值）都以 tick
  计，杜绝真实并发调度带来的不确定性；跨线程的安全性仅靠 `Network` 单把锁保证。
- **学习条目不被泛洪回程"迁移"**：源 MAC 已在 FDB 中时，从其他端口再次出现不会覆盖
  既有主机口绑定（真实终端迁移场景可通过先禁用旧端口再学习实现）。
- **fabric 交付在注入当拍内迭代到稳定**：由帧跳数 TTL 与无环生成树共同保证终止。
