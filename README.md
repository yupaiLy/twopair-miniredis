# twopair-miniredis

`twopair-miniredis` 是一个基于 Java 17 和 Netty 实现的 Redis 兼容服务端，包含手写 RESP2 编解码、String/List/Hash/Set、TTL、AOF 持久化和后台 Rewrite。服务默认监听 `6378`，可直接使用官方 `redis-cli` 连接，目前支持 29 个命令。

## 功能

- 手写 RESP2 编解码，处理 TCP 粘包、半包、递归数组和二进制安全的 Bulk String。
- 使用 Netty NIO 搭建服务端，拆分 boss、worker 和每条连接的 Pipeline。
- 使用 `ConcurrentHashMap` 保存 String、List、Hash、Set，并处理并发写入和类型检查。
- TTL 使用绝对毫秒时间，访问时惰性删除，后台线程定期清理。
- AOF 支持追加、重放、尾部损坏截断、`ALWAYS`/`EVERYSEC` 刷盘策略。
- `BGREWRITEAOF` 根据内存状态生成新 AOF，合并 Rewrite 期间的增量命令后原子替换旧文件。
- 使用 JUnit 4 和 Netty `EmbeddedChannel` 覆盖协议、命令、网络和持久化边界。

## 技术栈

| 项目 | 版本或实现 |
|---|---|
| Java | 17 |
| Netty | 4.2.5.Final，NIO |
| 日志 | SLF4J 2.0.7、Log4j2 2.24.3、MDC traceId |
| 构建 | Maven |
| 测试 | JUnit 4.13.2 |

## 请求链路

```text
redis-cli
    |
    | RESP bytes
    v
RedisServer
    |
    +-- boss EventLoopGroup：接收连接
    +-- worker EventLoopGroup：处理连接读写
            |
            v
        RespDecoder
        ByteBuf -> Resp
            |
            v
        CommandHandler
            |
            +-- CommandFactory -> Command -> RedisCoreImpl
            |                                |
            |                                +-- String / List / Hash / Set
            |                                +-- TTL
            |
            +-- AofPersistence -> appendonly.aof
            |
            v
        RespEncoder
        Resp -> ByteBuf
```

服务启动时先通过 [`AofReplay`](src/main/java/cn/twopair/persistence/aof/AofReplay.java) 恢复数据，再绑定端口。请求进入后，[`RespDecoder`](src/main/java/cn/twopair/server/codec/RespDecoder.java) 负责拆包，[`CommandFactory`](src/main/java/cn/twopair/command/CommandFactory.java) 创建本次请求的命令对象，[`CommandHandler`](src/main/java/cn/twopair/server/handler/CommandHandler.java) 执行命令并返回 RESP 响应。

写命令还会进入 AOF 路径。内存修改和 AOF 追加使用同一个 `AofPersistence` 锁，避免多个连接下的内存顺序与文件顺序不一致。

## 支持的命令

带 `*` 的命令会写入 AOF。

| 分类 | 命令 | 说明 |
|---|---|---|
| 连接 | `PING` | 检查连接 |
| 连接 | `SELECT 0` | 只兼容默认数据库 |
| String | `SET key value` `*` | 覆盖写入，不支持 EX/PX/NX/XX 选项 |
| String | `GET key` | 读取字符串 |
| String | `SETEX key seconds value` `*` | 写入并设置过期时间 |
| Key | `DEL key [key ...]` `*` | 删除一个或多个 key |
| Key | `TYPE key` | 返回 string、list、hash、set 或 none |
| Key | `SCAN cursor [MATCH pattern] [COUNT count]` | 分页扫描 key |
| TTL | `EXPIRE key seconds` `*` | 设置相对过期时间 |
| TTL | `PEXPIREAT key milliseconds-timestamp` `*` | 设置绝对毫秒过期时间 |
| TTL | `TTL key` | 不存在返回 -2，永久有效返回 -1 |
| List | `LPUSH key element [element ...]` `*` | 从头部插入 |
| List | `LPOP key` `*` | 从头部弹出 |
| List | `RPUSH key element [element ...]` `*` | 从尾部插入 |
| List | `RPOP key` `*` | 从尾部弹出 |
| List | `LLEN key` | 获取长度 |
| List | `LRANGE key start stop` | 支持负数下标 |
| Hash | `HSET key field value [field value ...]` `*` | 写入字段，返回新增数量 |
| Hash | `HMSET key field value [field value ...]` `*` | 兼容 Redis 历史命令，返回 OK |
| Hash | `HGET key field` | 读取字段 |
| Hash | `HDEL key field [field ...]` `*` | 删除字段 |
| Hash | `HLEN key` | 获取字段数量 |
| Hash | `HSCAN key cursor [MATCH pattern] [COUNT count]` | 分页扫描字段和值 |
| Set | `SADD key member [member ...]` `*` | 添加成员 |
| Set | `SREM key member [member ...]` `*` | 删除成员 |
| Set | `SISMEMBER key member` | 判断成员是否存在 |
| Set | `SCARD key` | 获取成员数量 |
| Set | `SSCAN key cursor [MATCH pattern] [COUNT count]` | 分页扫描成员 |
| AOF | `BGREWRITEAOF` | 提交后台 Rewrite 任务 |

类型不匹配时返回 Redis 的标准 `WRONGTYPE Operation against a key holding the wrong kind of value`。未注册的命令返回 `ERR 不支持的命令: COMMAND`。

## 实现说明

### RESP 与 TCP 半包

[`Resp`](src/main/java/cn/twopair/resp/Resp.java) 支持 RESP2 的 Simple String、Error、Integer、Bulk String 和 Array。数组使用递归解析，Bulk String 以 `byte[]` 保存，因此中文和任意二进制内容不会经过错误的字符转换。

解码开始前记录 `readerIndex`。如果数据还不完整，解析器抛出 `RespIncompleteException`，`RespDecoder` 回滚读指针并等待后续字节。协议格式错误则返回错误并关闭连接，避免剩余字节继续污染后面的命令。

### 数据与 TTL

[`RedisCoreImpl`](src/main/java/cn/twopair/core/impl/RedisCoreImpl.java) 使用 `ConcurrentHashMap<BytesWrapper, RedisData>` 保存数据。List、Hash 和 Set 的复合操作通过 `compute` 或 `computeIfPresent` 完成，key 存在但类型不符时抛出 `WrongTypeException`。

过期时间保存在每个值对象中，格式是绝对毫秒时间戳，`-1` 表示永久有效。读取路径会惰性删除过期值，`redis-expiration-cleaner` 每秒执行一次主动扫描。条件删除使用 `map.remove(key, oldValue)`，不会把其他线程刚替换的新对象误删。

### AOF 追加与恢复

默认 AOF 文件是 `data/appendonly.aof`，默认刷盘策略为 `ALWAYS`。

- `ALWAYS`：每批写命令追加后立即调用 `force(false)`，持久性更强，吞吐较低。
- `EVERYSEC`：请求线程只追加文件，`redis-aof-fsync` 每秒刷盘一次。正常关闭会补一次刷盘，断电或系统崩溃时仍可能丢失约一秒数据。

`SETEX` 和 `EXPIRE` 在落盘时转换为 `PEXPIREAT` 绝对时间，服务重启后不会重新获得一整段 TTL。启动重放遇到末尾半条 RESP 命令时，只截断损坏尾部，已经完整写入的命令仍会恢复。

### AOF Rewrite

[`AofRewriteCommandBuilder`](src/main/java/cn/twopair/persistence/aof/AofRewriteCommandBuilder.java) 从当前内存状态生成恢复命令：String 使用 `SET`，List 使用 `RPUSH`，Hash 使用 `HMSET`，Set 使用 `SADD`，TTL 使用 `PEXPIREAT`。集合每条命令最多携带 64 个元素。

`BGREWRITEAOF` 的执行过程如下：

1. 后台线程在 `AofPersistence` 锁内建立深拷贝快照。
2. 快照写入同目录临时文件，前台写命令继续追加旧 AOF，同时进入 Rewrite 增量缓冲区。
3. 后台线程再次获取锁，把增量命令写入临时文件并刷盘。
4. 临时文件通过 `ATOMIC_MOVE + REPLACE_EXISTING` 替换正式 AOF，后续写入切换到新文件。

正式 AOF 在提交成功前仍会正常接收写命令，但不会被临时文件提前覆盖。Rewrite 失败时删除临时文件并继续使用正式文件。与官方 Redis 不同，这里没有使用 `fork + COW`；建立快照和最终切换期间会阻塞写请求。

## 运行

先准备 Java 17 和 Maven，然后在项目根目录执行：

```bash
mvn test
mvn compile
mvn -q dependency:build-classpath -Dmdep.outputFile=target/runtime-classpath.txt
java -cp "target/classes:$(cat target/runtime-classpath.txt)" cn.twopair.Main
```

服务默认监听 `6378`：

```bash
redis-cli -h 127.0.0.1 -p 6378
```

切换到 `EVERYSEC`：

```bash
java -Dminiredis.aof.fsync=everysec -cp "target/classes:$(cat target/runtime-classpath.txt)" cn.twopair.Main
```

日志级别和目录也可以通过 JVM 参数调整：

```bash
java -Dminiredis.log.level=info -Dminiredis.log.dir=/tmp/miniredis-logs -cp "target/classes:$(cat target/runtime-classpath.txt)" cn.twopair.Main
```

## redis-cli 示例

```console
$ redis-cli -p 6378 SET name twopair
OK
$ redis-cli -p 6378 GET name
"twopair"
$ redis-cli -p 6378 SET name 李
OK
$ redis-cli -p 6378 GET name
"李"
$ redis-cli -p 6378 RPUSH queue first second third
(integer) 3
$ redis-cli -p 6378 LRANGE queue 0 -1
1) "first"
2) "second"
3) "third"
$ redis-cli -p 6378 HSET user:1 name ljj city beijing
(integer) 2
$ redis-cli -p 6378 SADD tags java redis netty
(integer) 3
$ redis-cli -p 6378 EXPIRE user:1 60
(integer) 1
$ redis-cli -p 6378 TTL user:1
(integer) 59
$ redis-cli -p 6378 BGREWRITEAOF
Background append only file rewriting started
```

## 测试与性能

当前版本运行 `mvn test` 共执行 208 个测试，结果为 0 failure、0 error。测试覆盖 RESP 完整包与半包、非法协议、命令参数、四种数据结构、TTL、Netty Pipeline、AOF 追加和重放、文件尾部截断、后台 Rewrite、增量缓冲及原子替换。

本机回环测试使用 Apple Silicon、`redis-benchmark -n 10000 -c 50 -d 64 -t set,get`，同一代码版本使用全新 AOF 文件：

| 刷盘策略 | SET ops/s | SET p50 | GET ops/s | GET p50 |
|---|---:|---:|---:|---:|
| `ALWAYS` | 272.11 | 146.047 ms | 133,333.33 | 0.183 ms |
| `EVERYSEC`，三轮中位数 | 112,359.55 | 0.431 ms | 178,571.42 | 0.151 ms |

这组数据说明当前实现的瓶颈很直接：`ALWAYS` 会让每个写请求等待磁盘刷盘，`EVERYSEC` 把刷盘移到后台后，SET 吞吐从 272.11 ops/s 提高到 112,359.55 ops/s。数字只代表这台机器上的本地测试，不等同于生产环境结果。测试方法、官方 Redis 对照和已知误差记录在[性能测试报告](docs/benchmark-report-2026-08-20.md)中。

## 项目边界

这是一个单节点教学实现，目前没有以下能力：

- 事务、Lua、主从复制、哨兵和集群。
- RDB、AOF `NO` 策略和 Redis 7 的多部分 AOF manifest。
- 多数据库，`SELECT` 只接受 `0`。
- `SET EX/PX/NX/XX`、阻塞 List、发布订阅、ZSet、Bitmap 和 Stream。
- AUTH、TLS，以及 `INFO`、`CONFIG`、`CLIENT` 等管理命令。
- 官方 Redis 的渐进式哈希桶 SCAN。当前 SCAN 会创建快照并排序，不适合大数据量。
- 流式 AOF 重放。当前实现一次读取整个文件，大文件会占用较多堆内存。

写路径依靠全局锁保持内存顺序与 AOF 顺序一致。AOF 追加失败时，已经完成的内存修改不会回滚，客户端会收到错误响应。项目没有认证机制，不应暴露到不可信网络。

## 源码阅读顺序

如果想按实现过程阅读，可以从这些阶段开始：

1. [核心存储](blogs/stage-01-core-foundation.md)
2. [RESP 协议](blogs/stage-02-resp-protocol.md)
3. [命令分发](blogs/stage-03-command-dispatch.md)
4. [TTL 与过期删除](blogs/stage-05-ttl-expiration.md)
5. [AOF 追加和重放](blogs/stage-06-aof-persistence.md)
6. [List、Hash、Set 与游标扫描](blogs/stage-07-data-structures.md)
7. [AOF 刷盘策略](blogs/stage-08-aof-performance.md)
8. [Netty Pipeline 的 Flush 合并](blogs/stage-09-netty-pipeline-performance.md)
9. [AOF Rewrite 与 BGREWRITEAOF](blogs/stage-10-aof-rewrite.md)

源码入口建议按 [`Resp`](src/main/java/cn/twopair/resp/Resp.java) -> [`CommandFactory`](src/main/java/cn/twopair/command/CommandFactory.java) -> [`RedisCoreImpl`](src/main/java/cn/twopair/core/impl/RedisCoreImpl.java) -> [`RedisPipeline`](src/main/java/cn/twopair/server/RedisPipeline.java) -> [`AofPersistence`](src/main/java/cn/twopair/persistence/aof/AofPersistence.java) 的顺序阅读。
