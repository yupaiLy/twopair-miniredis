# twopair-miniredis

用 **Java 17 + Netty 从零实现的迷你 Redis**：不使用现成 Redis 客户端/服务端实现，也不使用 Netty 内置 Redis 编解码器，手写 RESP 协议解析、命令分发、String/List/Hash/Set 四种数据结构、TTL 过期、AOF 持久化与后台 Rewrite，可直接用官方 `redis-cli` 连接使用。

## 这个项目解决什么问题

Redis 是后端工程师每天都在用的基础设施，但多数人只停留在"会调 API"。这个项目把 Redis 拆开重造一遍，回答这些问题：

- RESP 文本协议如何解决 **TCP 粘包/半包**，二进制安全的 BulkString 怎么解码？
- 一条 `SET key value` 从字节流进来到字节流出，中间经过哪些环节？**Reactor 网络模型**怎样组织这些环节？
- 内存字典 + 过期时间如何设计，才能在**并发访问**下正确实现惰性删除与周期删除，不误删刚写入的新值？
- 写命令如何既改内存又落盘，支持**重启恢复、TTL 不重新计时**，并在文件尾部出现半条命令时安全修复？
- AOF 持续增长时，如何从内存生成最小恢复命令，并在后台 Rewrite 期间保证并发写不丢失？

它是教学向的"轮子"：功能上是 Redis 的一个可用子集，实现上优先选择**最小、清晰、可验证**的方案，再通过测试和性能数据逐步优化，每个阶段的取舍都记录在 [blogs/](blogs/) 中。

## 技术栈

| 组件 | 选型 |
|---|---|
| 语言 / 运行时 | Java 17 |
| 网络框架 | Netty 4.2（NIO，主从 Reactor） |
| 日志 | SLF4J + Log4j2，MDC traceId 链路追踪 |
| 构建 / 测试 | Maven、JUnit 4 |

## 整体架构与请求执行链路

```
                ┌──────────────────────────────────────────────────────────────┐
                │                     RedisServer (Netty)                      │
                │                                                              │
   redis-cli    │  boss EventLoopGroup ×1          接收连接                     │
  ─────────────►│  worker EventLoopGroup ×N        连接读写                     │
   RESP 字节流   │                                                              │
  ◄─────────────│  每条连接的 Pipeline:                                          │
                │  ┌────────────────────────────────────────────────────┐      │
                │  │ RespDecoder   ByteBuf ──► Resp（半包回滚重试）      │      │
                │  │ RespEncoder   Resp   ──► ByteBuf                   │      │
                │  │ CommandHandler 分发执行 + 写命令AOF追加             │      │
                │  └────────────────────────────────────────────────────┘      │
                │            │ CommandFactory（命令注册表）                    │
                │            ▼                                                 │
                │  Command.handle(RedisCore)                                   │
                │            ▼                                                 │
                │  RedisCoreImpl: ConcurrentHashMap<BytesWrapper, RedisData>   │
                │      String / Hash / List / Set + 绝对毫秒过期时间            │
                │            ▲                       ▲                         │
                │  AofReplay（启动时重放）         AofFile（追加写命令）         │
                │            └──────► data/appendonly.aof ◄──────┘            │
                │                                                              │
                │  redis-expiration-cleaner（单线程定时，1s，主动过期扫描）      │
                └──────────────────────────────────────────────────────────────┘
```

一条 `GET key` 请求的完整链路：

```
redis-cli 发送 *2\r\n$3\r\nGET\r\n$3\r\nkey\r\n
   │
   ▼ ① RespDecoder（ByteToMessageDecoder）
      尝试解码一条完整 RESP；字节不全则回滚 readerIndex，等下一次数据到达
   ▼ ② CommandHandler（SimpleChannelInboundHandler<Resp>）
      校验必须是 RespArray → CommandFactory.from() 查注册表，
      每次请求 new 一个命令对象（命令有状态，不能复用）
   ▼ ③ Command.handle(RedisCore)
      命令对象解析参数 → 读/写 RedisCoreImpl
      └─ 写命令额外执行：synchronized(aofPersistence) { 内存修改 → AOF 追加 }
   ▼ ④ RespEncoder（MessageToByteEncoder）
      Resp 响应对象编码回字节流，writeAndFlush 给客户端
```

关键代码入口：[`RedisServer`](src/main/java/cn/twopair/server/RedisServer.java)（启动与 Pipeline 组装）、[`CommandHandler`](src/main/java/cn/twopair/server/handler/CommandHandler.java)（分发与异常分层）、[`CommandFactory`](src/main/java/cn/twopair/command/CommandFactory.java)（命令注册表）。

## 已支持命令（29 个）

| 分类 | 命令 | 说明 | 写命令（入 AOF） |
|---|---|---|---|
| 连接 | `PING` | 连接测试 | |
| 连接 | `SELECT 0` | 仅兼容默认库 | |
| String | `SET key value` | 不支持 EX/PX/NX 选项 | ✔ |
| String | `GET key` | | |
| String | `SETEX key seconds value` | 写入并设置 TTL | ✔（转写为 SET + PEXPIREAT） |
| 键/过期 | `EXPIRE key seconds` | 相对过期 | ✔（转写为 PEXPIREAT） |
| 键/过期 | `PEXPIREAT key ms-timestamp` | 绝对过期 | ✔ |
| 键/过期 | `TTL key` | -2 不存在 / -1 永久 / 剩余秒 | |
| 键 | `TYPE key` | string/hash/list/set，不存在返回 none | |
| 键 | `DEL key [key ...]` | 返回实际删除数 | ✔ |
| 键 | `SCAN cursor [MATCH p] [COUNT n]` | 游标分页 + MATCH 过滤 | |
| List | `LPUSH key v [v ...]` | 头部插入 | ✔ |
| List | `LPOP key` | 头部弹出，空列表自动删 key | ✔ |
| List | `RPUSH key v [v ...]` | 尾部插入 | ✔ |
| List | `RPOP key` | 尾部弹出，空列表自动删 key | ✔ |
| List | `LLEN key` | | |
| List | `LRANGE key start stop` | 支持负数下标 | |
| Hash | `HSET key f v [f v ...]` | 返回新增字段数 | ✔ |
| Hash | `HMSET key f v [f v ...]` | 兼容官方历史命令，返回 OK | ✔ |
| Hash | `HGET key f` | | |
| Hash | `HDEL key f [f ...]` | 空 Hash 自动删 key | ✔ |
| Hash | `HLEN key` | | |
| Hash | `HSCAN key cursor [MATCH p] [COUNT n]` | | |
| Set | `SADD key m [m ...]` | 返回新增成员数 | ✔ |
| Set | `SREM key m [m ...]` | 空 Set 自动删 key | ✔ |
| Set | `SISMEMBER key m` | | |
| Set | `SCARD key` | | |
| Set | `SSCAN key cursor [MATCH p] [COUNT n]` | | |
| 管理 | `BGREWRITEAOF` | 后台生成最小 AOF 并原子替换 | |

类型不匹配时返回标准 `WRONGTYPE Operation against a key holding the wrong kind of value`；未知命令返回 `ERR 不支持的命令: XXX`。

## 关键设计

### RESP 协议（手写编解码）

- 覆盖 5 种类型：`+` SimpleString、`-` Errors、`:` Integer、`$` BulkString、`*` Array，递归下降解码（[`Resp`](src/main/java/cn/twopair/resp/Resp.java)）。
- **半包处理**：不依赖 Netty 预置的 LengthField 拆包器（RESP 长度字段位置随类型变化），而是 `markReaderIndex()` + 自定义 `RespIncompleteException`：字节不全就回滚读指针返回 null，`RespDecoder` 不向下游传递对象，等下次 TCP 段到达后整体重新解析。
- **二进制安全**：BulkString 内容以 `BytesWrapper`（byte[] 封装）存储，UTF-8 仅用于命令名等控制字段，value 可以包含任意字节。
- **非法输入**：未知类型首字节、缺 CRLF、长度超过 `Integer.MAX_VALUE` 抛 `IllegalStateException`，经 `exceptionCaught` 回复 `Protocol error: ...` 并关闭连接，避免错误字节污染后续命令。

### Netty 网络层

- 主从 Reactor：boss ×1 只做 accept，worker ×N 负责连接读写；所有连接**共享同一个 `RedisCore` 与 `AofPersistence`**。
- 生命周期管理：`AtomicBoolean.compareAndSet` 保证 `close()` 幂等（shutdown hook、finally、重复调用只有一次生效）；`shutdownGracefully(2s, 12s)` 并行关闭两组线程；bind 结果显式检查 Future，准确区分端口占用与其他启动失败。
- 异常分层不污染 EventLoop：`WrongTypeException` → WRONGTYPE；`IllegalArgumentException`（参数/未知命令）→ ERR；AOF `IOException` → ERR 且不影响连接；其余 `RuntimeException` 兜底为通用错误，不向客户端泄漏内部信息。

### 日志与可观测性

- 每个请求生成独立 `traceId`，记录连接、命令名、响应类型和执行耗时，不记录 key/value 明文。
- 项目日志默认为 `DEBUG`，第三方库保留 `INFO` 及以上；支持通过 `-Dminiredis.log.level=info` 调整级别。
- 同时输出到控制台与 `logs/twopair-miniredis.log`，按天或 100 MB 滚动压缩，历史文件保留 30 天；可用 `-Dminiredis.log.dir=/path/to/logs` 修改目录。

### TTL 过期

- 每个值对象自带 `volatile long timeout` **绝对毫秒时间戳**（-1 表示永久），过期判定与重放都不依赖命令到达时间。
- **惰性删除 + 周期删除**双策略：访问时发现过期就地删除；独立单线程 `redis-expiration-cleaner` 以 fixedDelay 1s 全量扫描，扫描不占用 EventLoop，固定延迟避免任务堆积。
- **并发安全**：删除一律用 `map.remove(key, 旧值对象)` 条件删除——只有 Map 里仍是刚读到的对象才删，防止其他连接刚写入的新值被误删；TTL/读写走 `compute/computeIfPresent` 原子路径。
- 空集合（List/Hash/Set 弹空后）不保留 key，与官方语义一致；时钟通过 `LongSupplier` 注入，测试中可完全控制时间推进。

### AOF 持久化

- **追加命令而非数据**：写命令成功后按 RESP 原格式追加到 `data/appendonly.aof`，与官方 AOF 思路一致。
- **顺序一致性**：`synchronized(aofPersistence)` 内"先内存修改、后 AOF 追加"，多连接下内存修改顺序与文件顺序严格一致。
- **可配置刷盘**：`ALWAYS` 每批命令追加后立即 `force(false)`；`EVERYSEC` 只在请求线程追加文件，由独立的 `redis-aof-fsync` 线程每秒检查并刷入新增数据，避免每条写命令都在 Netty EventLoop 上等待磁盘，也避免空闲时无意义刷盘。
- **TTL 不重新计时**：`SETEX`/`EXPIRE` 落盘前由 `WriteCommand.toAofCommands()` 重写为 `SET` + `PEXPIREAT 绝对时间戳`，重启重放后剩余 TTL 与宕机前连续。
- **崩溃自愈**：启动时 [`AofReplay`](src/main/java/cn/twopair/persistence/aof/AofReplay.java) 在绑定端口**之前**重放全部命令；若文件末尾残留不完整命令（宕机写了一半），自动截断到最后一条完整命令后继续。
- **后台 Rewrite**：`BGREWRITEAOF` 从当前内存状态生成 String/List/Hash/Set 与 TTL 的最小恢复命令，写入同目录临时文件；Rewrite 期间的新写命令进入增量缓冲区，最终刷盘并原子替换正式 AOF。

## 快速开始

```bash
# 编译
mvn compile

# 导出依赖 classpath
mvn -q dependency:build-classpath -Dmdep.outputFile=target/runtime-classpath.txt

# 启动（默认端口 6378，AOF 文件 data/appendonly.aof）
java -cp "target/classes:$(cat target/runtime-classpath.txt)" cn.twopair.Main

# 使用EVERYSEC策略启动（操作系统崩溃或断电时，最多可能丢失约1秒尚未刷盘的数据）
java -Dminiredis.aof.fsync=everysec -cp "target/classes:$(cat target/runtime-classpath.txt)" cn.twopair.Main
```

日志显示 `Redis服务启动完成: localAddress=...:6378` 即就绪。

## redis-cli 演示

以下为真实运行输出（`redis-cli` 7.2.6）：

```console
$ redis-cli -p 6378 SET demo:name "mini-redis"
OK
$ redis-cli -p 6378 SETEX demo:session 60 sess-42
OK
$ redis-cli -p 6378 TTL demo:session
59
$ redis-cli -p 6378 TYPE demo:name
string
$ redis-cli -p 6378 LPUSH demo:queue a b c
3
$ redis-cli -p 6378 LRANGE demo:queue 0 -1
c
b
a
$ redis-cli -p 6378 HSET demo:user:1 name ljj city beijing
2
$ redis-cli -p 6378 HGET demo:user:1 name
ljj
$ redis-cli -p 6378 SADD demo:tags java redis netty
3
$ redis-cli -p 6378 SISMEMBER demo:tags redis
1
$ redis-cli -p 6378 SCAN 0 MATCH demo:* COUNT 20        # 游标 0 表示一趟扫完
0
demo:name
demo:queue
demo:session
demo:tags
demo:user:1
$ redis-cli -p 6378 BGREWRITEAOF
Background append only file rewriting started
$ redis-cli -p 6378 DEL demo:name demo:queue demo:user:1 demo:tags demo:session
5
```

AOF 重启恢复（写入 → 杀进程 → 重启 → 数据与剩余 TTL 均恢复）：

```console
$ redis-cli -p 6378 SETEX demo:cache 600 aof-data-1
OK
$ redis-cli -p 6378 LPUSH demo:list x y z
3
$ redis-cli -p 6378 TTL demo:cache
599
# ... 重启服务进程 ...
$ redis-cli -p 6378 GET demo:cache
aof-data-1
$ redis-cli -p 6378 LRANGE demo:list 0 -1
z
y
x
$ redis-cli -p 6378 TTL demo:cache      # 绝对时间恢复，TTL 没有重新计时
592
```

## 测试与性能

**测试**：`mvn test` —— **208 个用例 / 51 个测试类，全部通过**。覆盖 RESP 编解码（含半包、非法输入）、四种数据结构、RedisCore 并发与过期语义、29 个命令、Netty EmbeddedChannel 集成、AOF 追加、刷盘、重放、尾部截断自愈、后台 Rewrite、增量命令与原子替换。

**性能**（本机回环，`redis-benchmark -n 10000 -c 50 -d 64 -t set,get`，macOS Apple Silicon；同版本、同日志级别、全新临时AOF文件）：

| 配置 | SET ops/s | SET p50 | GET ops/s | GET p50 |
|---|---:|---:|---:|---:|
| `ALWAYS` | 272.11 | 146.047 ms | 133,333.33 | 0.183 ms |
| `EVERYSEC`（三轮中位数） | 112,359.55 | 0.431 ms | 178,571.42 | 0.151 ms |

`EVERYSEC` 的 SET 吞吐比同版本 `ALWAYS` 提升约 **413 倍**：请求线程只负责追加文件，后台线程每秒刷盘，不再让每条命令都在 Netty EventLoop 上等待磁盘。完整方法、历史基线和限制见 [性能测试报告](docs/benchmark-report-2026-08-20.md)。

## 已知限制

当前定位是教学项目，以下能力**未实现**：

- **无事务**（MULTI/EXEC/WATCH）、无 Lua 脚本
- **无主从复制、无哨兵、无集群**；单节点，AOF 是唯一持久化手段
- **无 RDB 快照**；AOF Rewrite 使用 Java 进程内全局锁建立快照，不是官方 Redis 的 `fork + COW` 和多部分 AOF manifest 模式
- AOF 刷盘策略目前支持 `ALWAYS` 和 `EVERYSEC`，暂不支持 `NO`
- 单数据库，`SELECT` 仅兼容 `0`
- `SET` 不支持 `EX/PX/NX/XX` 选项（TTL 请用 `SETEX`）
- `SCAN/HSCAN/SSCAN` 使用快照、排序和下标游标实现，不是官方 Redis 的渐进式哈希桶遍历，不适合大数据量
- 无阻塞命令（BLPOP/BRPOP）、发布订阅、ZSET/Bitmap/Stream 等结构
- 除 `BGREWRITEAOF` 外，无 `INFO/CONFIG/CLIENT` 等管理命令，无 AUTH 认证（请勿暴露到非信任网络）
- 写路径仍通过全局锁保证内存修改顺序与AOF顺序一致；AOF失败时内存已修改但不会回滚，客户端会收到错误响应

## 延伸阅读

各阶段的实现过程与取舍记录在 [blogs/](blogs/)：

- [stage-01 核心存储](blogs/stage-01-core-foundation.md)
- [stage-02 RESP 协议](blogs/stage-02-resp-protocol.md)
- [stage-03 命令分发](blogs/stage-03-command-dispatch.md)
- [stage-05 TTL 过期](blogs/stage-05-ttl-expiration.md)
- [stage-06 AOF 持久化](blogs/stage-06-aof-persistence.md)
- [stage-07 List、Hash、Set 与游标扫描](blogs/stage-07-data-structures.md)
- [stage-08 AOF 刷盘策略与性能优化](blogs/stage-08-aof-performance.md)
- [stage-09 Netty Pipeline 合并 Flush 性能优化](blogs/stage-09-netty-pipeline-performance.md)
- [stage-10 AOF Rewrite 与 BGREWRITEAOF](blogs/stage-10-aof-rewrite.md)
- [MiniRedis 性能测试报告](docs/benchmark-report-2026-08-20.md)
