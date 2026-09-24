# Redis Stream 异步指标与死信重放项目说明

## 1. 项目定位

智能面试平台包含简历分析、知识库向量化、知识库题目生成、文字面试评估和语音面试评估
五类耗时任务。原有实现已经使用 Redis Stream 解耦 HTTP 请求与后台处理，并具备状态流转、
Pending 消息认领和最多 3 次重试，但存在两个工程缺口：

1. 只能从日志判断任务是否成功，无法统一统计入队延迟、队列等待、处理耗时和失败恢复；
2. 格式错误或超过重试上限的消息最终 ACK 后缺少统一留存，无法集中查询和人工重放。

本次在公共生产者/消费者模板层实现统一观测与死信机制，业务消费者无需重复编写指标和
失败处理代码。

## 2. 核心链路

```text
HTTP业务请求
    │
    ▼
AbstractStreamProducer
    ├─ 写入 enqueuedAtEpochMs
    ├─ 记录入队成功/失败与入队耗时
    ▼
Redis Stream
    │ Consumer Group
    ▼
AbstractStreamConsumer
    ├─ 计算队列等待时间
    ├─ 幂等领取任务
    ├─ 执行业务并记录处理耗时/结果
    ├─ 失败且 retryCount < 3：重新入队
    └─ 解析失败、重试入队失败或达到上限
           │
           ▼
      Dead-letter Stream（审计，最多5000条）
           +
      RMapCache索引（查询/状态更新，单条TTL 7天）
           │
           ▼
      管理接口查询 → 分布式锁 → 人工重放 → 原Stream
```

## 3. 关键设计

### 3.1 ACK 顺序

达到重试上限时，顺序是：

1. 将原 Stream、Consumer Group、原消息 ID、重试次数、错误和原始载荷写入死信；
2. 死信写入成功后，将业务任务标记为 `FAILED`；
3. 最后 ACK 原消息。

如果死信写入失败，则不 ACK 原消息，使它仍留在 Pending List，后续可通过 `XAUTOCLAIM`
等价能力重新认领。这避免了“死信没保存，原消息却已经删除”的不可恢复丢失。

### 3.2 为什么同时使用 Stream 和 Hash 索引

- Dead-letter Stream 是只追加审计记录，保留失败发生时的历史；
- RMapCache 索引以 UUID 为键，便于按 ID 查询和更新 `PENDING/REPLAYING/REPLAYED` 状态；
- Stream 最大长度为 5000，索引单条 TTL 为 7 天，防止无限增长。

这是审计日志与可变操作视图的分工，不是为了追求严格事务一致性。Redis Stream 与 Hash
并非原子写入；生产环境可进一步使用 Lua 脚本或事务将两次写入合并。

### 3.3 重放语义

- 使用 `async:task:dead-letter:replay:{deadLetterId}` 分布式锁避免并发重复点击；
- 重放前状态从 `PENDING` 改为 `REPLAYING`；
- 重放消息将 `retryCount` 重置为 0，并写入新的入队时间和 `replayedFrom`；
- 成功后记录新消息 ID，状态改为 `REPLAYED`；失败则恢复为 `PENDING`；
- 系统提供的是 at-least-once，不承诺 exactly-once，业务侧仍需通过任务 ID 和状态流转幂等。

### 3.4 数据安全

死信内部需要保留原载荷才能重放，但查询接口不会直接返回 `content`，只显示
`<redacted:N chars>`；其他字段最多显示 120 个字符。生产环境还应增加管理员 RBAC、Redis
访问控制、敏感字段加密或不可逆脱敏。

## 4. Micrometer 指标

| 指标 | 标签 | 含义 |
| --- | --- | --- |
| `app.async.task.enqueue` | task, outcome | 入队成功或失败次数 |
| `app.async.task.enqueue.latency` | task, outcome | Redis Stream 写入耗时 |
| `app.async.task.queue.wait` | task | 消息从入队到开始处理的等待时间 |
| `app.async.task.processing` | task, outcome | 消费端处理耗时 |
| `app.async.task.events` | task, event | completed、retried、skipped、invalid、dead_lettered 等事件 |
| `app.async.task.replay` | task, outcome | 人工重放成功或失败次数 |

指标通过 `/actuator/prometheus` 暴露。当前应用本身不保存长期时序数据，实际部署需要
Prometheus 定期抓取，并由 Grafana 展示 P50/P95/P99、成功率、重试率和死信增长趋势。

## 5. 管理与演示接口

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| GET | `/api/admin/async/dead-letters?status=PENDING&limit=50` | 查询死信摘要 |
| GET | `/api/admin/async/dead-letters/stats` | 查询各状态数量和审计流长度 |
| POST | `/api/admin/async/dead-letters/{id}/replay` | 加锁后重放指定死信 |
| POST | `/api/demo/async/failures` | 仅 isolated Profile 可用，生成合成故障 |
| GET | `/api/demo/async/tasks/{taskId}` | 仅 isolated Profile 可用，查看演示任务状态 |

管理接口已经限流，但项目目前没有完整 RBAC，因此正式上线前必须限制为管理员或内网访问。

## 6. 隔离运行方式

在仓库根目录运行隔离 Profile，避免占用日常开发环境：

隔离 Profile 使用：

- HTTP 端口 `18080`，不占用原应用 `8080`；
- PostgreSQL 独立 schema `interview_async_demo`；
- Redis 独立逻辑库 DB 1；
- pgvector 表位于独立 schema；
- 假模型 Key，故障测试不调用外部 LLM；
- 独立临时 Provider 配置路径；
- 不自动创建或写入 RustFS bucket。

启动示例：

```powershell
.\scripts\run-isolated.ps1 `
  -ConnectionEnvFile 'C:\path\to\your\source\.env'
```

脚本只把 5 个 PostgreSQL 连接变量读入当前进程，不复制原 `.env`，也不会读取模型 Key。

运行故障恢复基准：

```powershell
.\scripts\benchmark_dlq.ps1 -Count 100 -ReplayIntervalMs 450
```

## 7. 已验证结果

### 7.1 自动化测试

- 完整后端测试：288 项；242 项通过，46 项条件跳过，0 失败，0 错误；
- 新增核心测试：6 项全部通过；
- 覆盖死信写入、TTL索引、加锁重放、重复重放拒绝、ACK顺序和死信写入失败保护。

### 7.2 本机隔离故障实验

环境：单机、Spring Boot 单实例、Redis DB 1、合成任务、不调用外部模型。

| 指标 | 结果 |
| --- | ---: |
| 合成失败任务数 | 100 |
| 死信成功捕获 | 100/100，100% |
| 重放请求成功 | 100/100，100% |
| 重放后任务完成 | 100/100，100% |
| 故障进入死信 P50 / P95 / Max | 17.43 / 35.18 / 157.49 ms |
| 重放到完成 P50 / P95 / Max | 16.73 / 24.84 / 41.15 ms |

这些数字只代表本机合成 Redis Stream 故障路径，不代表包含 LLM、文档解析或向量化的业务任务
耗时。简历中必须同时写出“100条合成故障样本”和“本机隔离测试”。

## 8. 推荐简历写法

### 后端方向（推荐两条）

> 基于模板方法统一改造5类 Redis Stream 异步任务，在生产/消费端接入 Micrometer，采集入队耗时、队列等待、处理耗时、重试及成功/失败事件，并通过 Prometheus 暴露指标。

> 设计死信留存与人工重放机制，对消息解析失败、重试入队失败及超过3次重试的任务执行“先写死信、后ACK”；结合7天TTL索引、分布式锁和内容脱敏接口，在100条本机合成故障测试中实现100%死信捕获及100%重放恢复，重放到完成P95为24.84 ms。

### 后端 + Agent 方向（可替换第二条）

> 为简历分析、RAG向量化、智能出题及面试评估等5类AI异步任务构建统一可观测与故障恢复层，记录队列等待、模型任务处理结果及重试事件；通过死信流和人工重放隔离外部模型超时、格式异常等故障，100条合成故障样本全部恢复。

不要写成以下表达：

- “保证消息绝不丢失”——Redis、双写、进程崩溃和基础设施故障下不能做这种绝对承诺；
- “实现 exactly-once”——当前是 at-least-once + 业务幂等；
- “线上恢复率100%”——数据来自本机合成测试，不是线上生产；
- “LLM任务P95只有24.84 ms”——该数字只测故障重放框架，不包含LLM；
- “自研消息队列”——使用的是 Redis Stream，不是自研 MQ。

## 9. 面试必须能讲清楚的问题

### Q1：为什么选择 Redis Stream，而不是 Kafka/RabbitMQ？

项目已经使用 Redis 做缓存、限流和分布式锁，任务规模有限，引入 Redis Stream 能降低部署和
运维成本，同时获得 Consumer Group、Pending List、ACK 和消息认领能力。如果需要跨机房、
超大吞吐、长期消息保留和更成熟的消息治理，会考虑 Kafka；需要复杂路由和成熟死信交换机时
会考虑 RabbitMQ。

### Q2：为什么必须先写死信再 ACK？

如果先 ACK，随后死信写入失败，原消息会从 Pending 中消失且没有失败副本，无法恢复。先写
死信，写入失败就不 ACK，消息仍能在超过 idle 时间后被其他消费者认领。

### Q3：为什么不是 exactly-once？

Redis Stream 消费、数据库更新和死信索引写入不是一个跨资源事务。消费者可能在业务成功后、
ACK 前崩溃，从而被重复投递。因此采用 at-least-once，通过任务 ID、状态条件更新和业务唯一键
保证重复处理不产生重复结果。

### Q4：重放为什么要分布式锁？

两个管理员可能同时点击重放。以 deadLetterId 为粒度加锁，并检查状态必须为 `PENDING`，可以
减少同一死信被并发重放。锁不能解决进程在“消息发送成功、状态更新前”崩溃的所有问题，所以
业务消费者仍需幂等。

### Q5：为什么死信还要 Hash 索引？

Stream 擅长追加和按游标消费，不擅长频繁按业务 ID 修改状态。Hash 索引便于按 UUID 查询和把
状态从 PENDING 更新为 REPLAYED；Stream 保留原始审计历史。代价是两份数据存在最终一致性问题。

### Q6：100%恢复率是否说明系统绝不会失败？

不能。100%只表示100条本机合成故障在Redis可用、单实例和固定演示逻辑下全部恢复。真实LLM
超时、网络分区、Redis故障、进程崩溃和毒消息还需要更复杂的故障注入与长期测试。

### Q7：P95是怎么测的？

脚本记录每个任务从故障请求发出到死信可查询的时间，以及从重放请求发出到任务状态变成
COMPLETED的时间；排序后取第95百分位。重放速率设为450 ms一次，是为了低于接口“IP每秒3次”
的滑动窗口限流。

### Q8：当前实现还有哪些不足？

1. Stream 与索引双写不是原子操作，可用 Lua 或事务增强；
2. 重试是立即重新入队，缺少指数退避和随机抖动；
3. 管理接口只有限流，没有完整 RBAC；
4. 死信载荷在 Redis 内仍是明文，需要按数据等级加密或脱敏；
5. 每类消费者当前是单工作线程，吞吐扩展依赖增加实例或调整消费线程模型；
6. 指标需要外部 Prometheus 持久化，当前没有 Grafana 告警规则；
7. 尚未测试真实LLM超时、Redis重启和进程在关键步骤崩溃等场景。

## 10. 面试前最低掌握清单

在把这段写进简历前，至少要能不看代码解释：

1. Redis Stream 的 Consumer Group、Pending、ACK、XAUTOCLAIM 分别解决什么问题；
2. 为什么重试次数是消息字段，重放时为什么重置为0；
3. 三种进入死信的条件；
4. 死信失败时为什么保留原消息；
5. at-least-once 与业务幂等的关系；
6. Stream审计与Hash索引的分工及双写风险；
7. Micrometer Counter和Timer分别记录什么，P95如何计算；
8. 本机100条实验的边界，哪些结论不能外推。

如果上述任一项不能用自己的话解释，简历先采用较保守的一条版本，不要同时堆叠“死信、分布式
锁、幂等、可观测性、100%恢复”等所有关键词。
