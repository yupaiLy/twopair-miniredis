# MiniRedis 性能测试报告与官方 Redis 对照

> 日期：2026-08-20 · 代码版本：`stage/07-data-structures` @ `fe69cc6` · 测试人：Claude（自动化）

## 1. 测试环境与方法

| 项 | 值 |
|---|---|
| 机器 | macOS Darwin 25.2.0，Apple Silicon (arm64)，localhost 回环 |
| MiniRedis | Java 17 (Corretto 17.0.13)，Netty 4.2.5.Final，默认启动 = AOF 开启每批 `force` |
| 官方 Redis | 7.2.6 (Homebrew)，三种持久化配置分别测试 |
| 压测工具 | redis-benchmark 7.2.6 |
| 统一参数 | `-n 10000 -c 50 -d 64`；每轮前先 2000 次预热排除 JIT/缓存噪声 |

公平性处理：官方 Redis 通过 `CONFIG SET` 临时切换持久化配置测完即恢复（`appendonly no` / `appendfsync everysec` 原值）；MiniRedis 用同一台机器同时段分实例测试（AOF 开=6378，AOF 关=6380）。

## 2. 功能测试结果

`mvn test`：**158 个用例 / 40 个测试类，全部通过（0 失败、0 错误、0 跳过）**。覆盖 RESP 编解码（半包/非法输入）、四种数据结构、并发与过期语义、25 个命令的参数校验与执行、EmbeddedChannel 集成、AOF 追加与重放（含尾部截断自愈）。

## 3. 性能对比总表

### 3.1 五命令矩阵（ops/s，p50 延迟 ms）

| 配置 | SET | GET | LPUSH | HSET | SADD |
|---|---:|---:|---:|---:|---:|
| **MiniRedis** AOF 开（默认） | 259 / 152 | 67,568 / 0.6 | 254 / 153 | 255 / 152 | 251 / 152 |
| **官方** AOF 开 + always（对齐 MiniRedis 语义） | 7,446 / 6.7 | 131,579 / 0.2 | 8,058 / 6.1 | 7,874 / 6.5 | 6,596* / 7.8 |
| **官方** AOF 开 + everysec（官方默认） | 204,082 / 0.14 | 204,082 / 0.14 | 204,082 / 0.14 | 204,082 / 0.14 | 200,000 / 0.14 |
| **MiniRedis** AOF 关（内存上限） | 43,290 / 1.1 | 46,729 / 0.9 | 50,761 / 0.8 | 54,054 / 0.8 | 54,645 / 0.7 |
| **官方** AOF 关（用户现状，RDB） | 200,000 / 0.14 | 196,078 / 0.14 | 200,000 / 0.14 | 151,515 / 0.14 | 204,082 / 0.14 |

\* SADD 特殊值验证过：benchmark 重复添加同一成员时命令未产生修改，官方 Redis 跳过 AOF 传播（测得 108,696 rps）；换成随机成员（`-r`，每次真实写入）后跌至 6,596 rps，与 SET/LPUSH/HSET 一致。MiniRedis 每条 SADD 都真实落盘，故 251 rps 是诚实数字。

### 3.2 Pipeline（P=16，50 连接）

| 配置 | SET | GET |
|---|---:|---:|
| MiniRedis AOF 开 | 257（+0%） | 72,464（+7%） |
| MiniRedis AOF 关 | 68,493（**+58%**） | 78,740（**+68%**） |
| 官方 AOF 关 | 2,500,000（**+12.5×**） | 3,333,333（**+17×**） |
| 官方 always | 101,010（+13.6×） | 769,231 |

### 3.3 并发扩展性（MiniRedis AOF 关，SET / GET ops/s）

| 连接数 | SET | GET |
|---:|---:|---:|
| 10 | 56,497 | 59,880 |
| 50 | 43,290 | 46,729 |
| 200 | 57,803 | 57,803 |

吞吐从 10 连接起就饱和，加连接不涨吞吐（±20% 为单次运行波动）。

## 4. 测试报告暴露的问题

### P1（最严重）：写路径串行同步刷盘，完全无法利用并发

同样受 fsync 物理约束，差距 **28 倍**：

- MiniRedis：259 rps ≈ 1 / 3.9ms（单次 `channel.force` 耗时）。`synchronized(aofFile)` 把 50 个连接的写命令排成一队，每条命令独占一次刷盘 → 吞吐恒等于 1/刷盘延迟，与并发数无关；p50 = 152ms 即排队深度 × 单次刷盘。
- 官方 always：7,446 rps ≈ 50 / 6.7ms。fsync 期间其他客户端的写命令先追加到缓冲，**一次 fsync 摊到 50 个并发写**（组提交），每个客户端只等一轮 fsync（p50 = 6.7ms）。

结论：MiniRedis 不是"硬盘慢"，是**把并发写串行化成了一根独木桥**。

### P2：Pipeline 形同虚设

- 官方 P16 提升 12-17 倍，MiniRedis 只提升 58-68%，绝对值差 **35-40 倍**。
- 代码层原因：`CommandHandler.channelRead0` 对每条命令执行 `writeAndFlush`——即使客户端一个 TCP 包发来 16 条命令，也会产生 16 次独立的响应编码 + flush 系统调用；解码侧 `RespDecoder` 每条命令独立触发完整解析流程，无批量路径。

### P3：内存路径每请求固定开销过大（4-5 倍差距）

- AOF 关时 MiniRedis 43-57k vs 官方 151-204k。
- 并发扩展性测试（3.3 节）证明瓶颈不在 Netty 线程模型——10 连接就饱和，是**单请求 CPU 开销**。代码审查的候选点：
  - 每条命令两次 `MDC.put/remove` + `TraceIdGenerator` 生成（无论日志级别是否启用）；
  - 每请求 `new` Command 对象 + 参数解析中的多次 `toUtf8String()`/`toUpperCase` 分配；
  - `BytesWrapper.hashCode` 无缓存，每次 ConcurrentHashMap/HashMap/HashSet 操作都 O(len) 重算；
  - 响应对象树（RespArray/BulkString）逐请求新建，无池化。

### P4：SCAN/HSCAN/SSCAN 每次调用全量快照 + 全量排序（代码审查发现，未压测）

`RedisCoreImpl.scanKeys()` 每次 SCAN 都**遍历全部 key → 全量排序 O(N log N) → 切页返回**，COUNT 只是返回条数提示，单次调用成本与库大小成正比。百万 key 时，一个客户端反复 SCAN 就能吃满 CPU。HSCAN/SSCAN 的 `entriesSnapshot()`/`membersSnapshot()` 同样先全量拷贝再分页。官方实现是反向二进制游标遍历字典桶，单次成本只与 COUNT 相关。

### P5：AOF 重放整文件读入内存

`AofReplay` 用 `Files.readAllBytes` 一次性载入：堆内同时存在整个文件字节 + 解码对象树。AOF 增长到 GB 级时启动有 OOM 风险（且当前无 AOF 重写，文件只增不减，问题会累积）。

### P6：周期过期全量扫描

`removeExpired()` 每秒遍历**所有** key，O(N)/秒，与过期 key 数量无关。官方 active expire cycle 只随机采样并设 CPU 时间预算（默认每周期 1ms）。

## 5. 官方对照给出的关键启示

| 官方数据 | 说明 |
|---|---|
| always：7.4k | fsync 是物理约束，官方也躲不掉——**"每次修改必须落盘"注定是百到千级 rps** |
| everysec：204k | 把刷盘挪到后台线程、每秒一次，写吞吐直接回到内存速度，代价是最多丢 1 秒 |
| always+P16：101k | 组提交 × pipeline 叠加后，即使 always 也能上 10 万——串行 fsync 与批量响应是两个独立的乘数 |

MiniRedis 当前写路径 259 rps 的问题**不在语义选择，在实现方式**：同样的 always 语义，官方用组提交做到 28 倍。

## 6. 优化路线图（按性价比排序）

### O1 AOF 后台刷盘 + 组提交（预期收益最大）

- **做法**：写命令只 `write` 进 channel 不 `force`；新增后台刷盘线程（everysec：每秒 force 一次；always：攒并发写为一组，一轮 fsync 后统一放行等待的请求）。
- **改动点**：`AofFile`（拆分 append/flush）、`CommandHandler.executeCommand`（锁粒度）、新增 bio 线程。
- **预期**：everysec 语义 SET 259 → **~40k**（撞上内存上限，见 3.1 节 AOF 关数据）；always+组提交 → ~5-8k（对齐官方）。
- **验证**：重跑 3.1/3.2 矩阵 + `kill -9` 崩溃恢复测试（everysec 最多丢 1s 必须成立）。

### O2 批量响应回写（激活 pipeline）

- **做法**：`writeAndFlush` 改为 `write` + `FlushConsolidationHandler`（Netty 内置）或 `channelReadComplete` 统一 flush。
- **改动点**：`CommandHandler`、`RedisServer` pipeline 装配。
- **预期**：P16 SET 68k → 100k+；普通模式小幅收益（省 flush 系统调用）。
- **风险**：flush 合并增加尾延迟，需要折中合并阈值（官方也是"读一批、处理一批、回一批"）。

### O3 削减每请求固定开销

- traceId/MDC 只在日志启用时生成（`isDebugEnabled` 守卫或惰性生成）；
- `BytesWrapper` 构造时缓存 `hashCode`（仿 String）；
- 命令名解析避免每请求 `toUpperCase` 分配（注册表查询用忽略大小写比较或缓存）。
- **预期**：内存路径 50k → 70-90k（需实测；目标把 4 倍差距缩小一半）。

### O4 SCAN 改字典桶游标

- `scanKeys()` 去掉全量排序；游标 = 上次遍历到的桶位置，MATCH 在遍历中过滤。ConcurrentHashMap 弱一致遍历恰好符合 SCAN 的保证语义。
- **预期**：单次 SCAN 从 O(N log N) 降到 O(扫描间隔)；HSCAN/SSCAN 去掉快照拷贝。

### O5 AOF 重放流式化

- `FileChannel` + 固定 64KB 缓冲逐命令 `Resp.tryDecode`（解码器本就支持流式语义），内存占用从 O(文件大小) 降到 O(最大单命令)。

### O6 过期清理改采样

- `removeExpired()` 改为每周期随机采样 N 个 key + CPU 时间预算，全量扫描降频（如 10s）兜底，对齐官方 active expire cycle。

### O7（长期）编码路径池化

- 响应对象/ByteBuf 池化、编码合并（多条响应一次编码）。收益中等、复杂度高，放最后。

## 7. 方法学局限

- 每配置单轮运行，MiniRedis 内存路径波动约 ±20%（3.3 节 c10/c50/c200 的差异属噪声）；官方 200k 级数字在 n=10000 下测量窗口仅 ~50ms，同样有波动（HSET 151k vs SET 200k）。
- localhost 回环不含真实网络 RTT；macOS 的 fsync 语义与 Linux 不同，绝对值仅用于**相对比较**，不代表 Linux 生产环境数值。
- 未测大 value（-d 512+）、SCAN 大数据集（P4 为代码审查结论）、长时间内存增长。
