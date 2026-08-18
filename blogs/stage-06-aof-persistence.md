# 从 0 手敲一个 Mini Redis：第六阶段，我实现了 AOF 持久化与重启恢复

## 摘要

完成 TTL 之后，我的 Mini Redis 已经能够通过 `redis-cli` 处理 `PING`、`SET`、`GET`、`EXPIRE`、`TTL` 和 `SETEX`，但所有数据都只存在于内存中。只要服务器停止，之前写入的数据就会全部消失。

第六阶段，我实现了一套以正确性优先的同步 AOF 持久化机制，打通了下面这条完整链路：

```text
客户端写命令
  -> 执行命令修改内存
  -> 转换成适合持久化的RESP命令
  -> 追加并刷入AOF文件
  -> 服务器重启时按顺序重放
  -> 恢复RedisCore中的数据
```

这一阶段不仅实现了最基础的命令追加和启动重放，还进一步处理了几个容易被忽略的问题：

- 如何只持久化写命令，而不记录 `GET`、`PING` 等读命令
- `SETEX` 和 `EXPIRE` 为什么不能直接原样写入 AOF
- 为什么要引入官方命令 `PEXPIREAT`
- AOF 最后一条命令只写了一半时如何恢复
- 多个 Netty Worker 并发写入时，如何保证内存顺序与 AOF 顺序一致
- AOF 文件应该如何接入服务器启动和关闭生命周期

最终，项目通过了 62 个自动化测试，并完成了真实的“写入、停止、重启、读取”验收。

---

## 一、为什么内存数据库还需要持久化

第五阶段完成后，所有数据都保存在 `RedisCoreImpl` 的 `ConcurrentHashMap` 中。

它的优势是读写快，但问题也很明显：

```text
JVM退出 -> 内存释放 -> 数据全部丢失
```

为了让服务器重启后可以恢复数据，我需要把运行期间发生的写操作保存到磁盘。

Redis 常见的持久化方式包括 RDB 和 AOF：

- RDB 更像某个时间点的数据快照
- AOF 记录的是导致数据变化的写命令

这一阶段我选择先实现 AOF，因为当前项目已经具备完整的 RESP 编解码和命令执行链路，可以直接复用现有能力：

```text
写入时：RespArray -> RESP字节 -> AOF
恢复时：AOF字节 -> RespArray -> Command -> RedisCore
```

写路径和恢复路径天然对称，这也是 AOF 最容易理解的地方。

---

## 二、先用 WriteCommand 区分读写命令

并不是所有命令都需要写入 AOF。

例如：

```text
PING、GET、TTL -> 只读取状态
SET、SETEX、EXPIRE、PEXPIREAT -> 会修改数据
```

如果通过命令名写很多判断：

```java
if (command.type() == CommandType.SET
        || command.type() == CommandType.SETEX
        || command.type() == CommandType.EXPIRE) {
    // 写AOF
}
```

那么每增加一个写命令，都要回到处理器里修改判断。

我为此增加了 `WriteCommand` 接口：

```java
public interface WriteCommand extends Command {

    default List<RespArray> toAofCommands(
            RespArray originalCommand,
            RedisCore redisCore
    ) {
        return List.of(originalCommand);
    }
}
```

它有两个职责：

```text
类型职责：标记当前命令会修改数据
转换职责：决定当前命令应该以什么形式写入AOF
```

普通 `SET` 不需要特殊处理，默认保存客户端原始命令即可。

带相对过期时间的 `SETEX` 和 `EXPIRE` 则可以覆盖 `toAofCommands()`，转换成不会因重启而改变语义的命令。

---

## 三、AofFile：将 RESP 命令追加到磁盘

我新增了 `AofFile`，它只负责一件事：

```text
把RespArray编码成RESP字节，然后追加到AOF文件
```

构造器通过 `FileChannel` 以追加模式打开文件：

```java
this.channel = FileChannel.open(
        absolutePath,
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
        StandardOpenOption.APPEND
);
```

这样文件不存在时会自动创建，已经存在时则从末尾继续写入，不会覆盖历史命令。

编码时直接复用第二阶段完成的 RESP 编码器：

```java
for (RespArray command : commands) {
    Resp.encode(command, buffer);
}
```

这意味着网络协议和磁盘格式使用同一套编码规则，不需要再设计一份私有序列化格式。

### 为什么 FileChannel.write() 要循环

`FileChannel.write()` 单次调用不保证写完缓冲区中的全部内容，所以必须检查剩余字节：

```java
while (byteBuffer.hasRemaining()) {
    channel.write(byteBuffer);
}
```

如果只调用一次 `write()`，在某些情况下可能留下半条 RESP 命令。

### 为什么增加 appendAll()

`SETEX` 持久化时会被转换为两条命令。如果分别调用两次 `append()`，不仅会重复刷盘，还可能让其他连接的写命令插入中间。

因此我增加了批量追加：

```text
一批命令 -> 同一个ByteBuf -> 连续写入 -> 只刷盘一次
```

当前阶段使用最保守的同步策略：

```java
channel.force(false);
```

它能提高持久性，但也会阻塞当前线程。这是当前版本明确保留的性能优化点。

---

## 四、AofReplay：启动时重放历史命令

只有写入没有读取，持久化仍然没有形成闭环。

我新增了 `AofReplay`，启动时完成以下步骤：

```text
读取AOF全部字节
  -> Resp.tryDecode()
  -> 得到RespArray
  -> CommandFactory创建命令
  -> command.handle(redisCore)
```

核心代码可以概括为：

```java
while (buffer.isReadable()) {
    Resp resp = Resp.tryDecode(buffer);
    RespArray commandArray = (RespArray) resp;
    Command command = CommandFactory.from(commandArray);
    command.handle(redisCore);
}
```

重放过程不会经过网络，也不会给客户端发送响应。它只是在服务器开始接收连接之前，按照原始顺序重新执行写命令。

这让我更清楚地理解了 AOF 的本质：

```text
AOF保存的不是Java对象，而是可以重新构造数据库状态的操作历史
```

---

## 五、SETEX 为什么不能直接写入 AOF

这是这一阶段最关键的 TTL 问题。

假设服务器在第 1 秒执行：

```bash
SETEX session 10 temporary
```

正确的绝对过期时间是第 11 秒。

如果 AOF 直接记录原始命令：

```text
SETEX session 10 temporary
```

服务器在第 5 秒重启时会重新执行它，过期时间就会变成第 15 秒。

```text
原过期点：1 + 10 = 11
重放后：  5 + 10 = 15
```

这样每重启一次，TTL 都会被延长。

### 使用绝对过期时间

为了解决这个问题，我将 `SETEX` 转换成：

```text
SET session temporary
PEXPIREAT session 11000
```

`PEXPIREAT` 不是自定义命令，而是 Redis 官方的绝对毫秒时间过期命令。

无论 AOF 在什么时候重放，保存的过期时间始终是 `11000ms`，不会重新获得完整 TTL。

`EXPIRE` 同样需要转换：

```text
EXPIRE name 10
```

写入 AOF 时变成：

```text
PEXPIREAT name 绝对毫秒时间戳
```

这也是为什么我在 `RedisCore` 中增加了 `expireAt()`，并实现了 `PExpireAt` 命令。

---

## 六、如何处理 AOF 末尾的半条命令

即使代码循环写入并主动刷盘，进程异常退出或机器突然断电时，AOF 最后一条命令仍然可能只写入一部分。

例如：

```text
[完整SET命令]
[完整EXPIRE命令]
[只写了一半的SET命令]
```

前两条命令是可靠的，最后一条无法解析。

我在重放时维护 `lastValidOffset`：

```java
command.handle(redisCore);
replayedCount++;
lastValidOffset = buffer.readerIndex();
```

如果 `Resp.tryDecode()` 返回 `null`，说明读取到的是末尾半包。这时把文件截断到最后一条完整命令结束的位置：

```java
channel.truncate(lastValidOffset);
channel.force(true);
```

这里所谓的“修复”，不是猜测残缺命令原本是什么，而是：

```text
保留可以确定完整的数据
删除无法安全恢复的尾部字节
让AOF重新成为合法的RESP命令流
```

### 为什么中间损坏不能这样处理

如果非法字节出现在文件中间，我不会自动跳过或截断。

因为此时无法确定后面的命令边界，也不能确认哪些数据仍然可信。因此当前策略是：

```text
末尾半包 -> 自动截断
中间非法RESP -> 启动失败，保留原文件
```

---

## 七、将 AOF 接入 RedisServer 生命周期

AOF 文件不是某个连接的私有资源，而是整个服务器共享的资源。

我将它接入 `RedisServer` 后，启动顺序变成：

```text
重放历史AOF
  -> 打开AOF文件
  -> 创建Boss和Worker
  -> 绑定端口
  -> 开始接收客户端连接
```

必须先恢复再绑定端口，否则客户端可能在数据只恢复一半时发来请求。

每个连接会创建自己的 `CommandHandler`，但这些处理器共享：

```text
同一个RedisCore
同一个AofFile
```

关闭时则反过来：

```text
停止接收新连接
  -> 等待Worker关闭
  -> 关闭AofFile
```

如果先关闭 AOF，Worker 中尚未结束的命令就可能向已经关闭的文件写入。

---

## 八、多 EventLoop 下的写入顺序问题

实现完基本持久化后，我又遇到了一个更隐蔽的问题。

Netty 的不同连接可能运行在不同 Worker EventLoop 上，因此两个客户端可以并行执行写命令。

可能出现这样的顺序：

```text
连接A：内存写入first  -> 暂停             -> AOF写入first
连接B：                  内存写入second -> AOF写入second
```

此时内存最终值是 `second`，但 AOF 顺序可能是：

```text
SET name second
SET name first
```

重启后最终值会变成 `first`，与服务器停止前不同。

### 把内存修改和 AOF 追加放进同一个临界区

所有 `CommandHandler` 共享同一个 `AofFile`，所以当前阶段直接使用它作为写锁：

```java
synchronized (aofFile) {
    Resp response = command.handle(redisCore);
    List<RespArray> aofCommands =
            writeCommand.toAofCommands(originalCommand, redisCore);
    aofFile.appendAll(aofCommands);
    return response;
}
```

这样一个写命令的完整过程变成不可插入的整体：

```text
修改内存 -> 生成AOF命令 -> 追加AOF
```

这保证了内存执行顺序与持久化顺序一致。

需要说明的是，这个方案优先保证正确性，会让启用 AOF 时的写命令串行执行。对于当前教学项目是合理取舍，但不是最终高性能方案。

---

## 九、我如何测试这一阶段

这一阶段最终拥有 62 个自动化测试，AOF 相关测试重点覆盖：

- 读命令不会写入 AOF
- 执行失败的写命令不会写入 AOF
- 多条命令保持 RESP 格式和追加顺序
- `appendAll()` 能批量编码和刷盘
- AOF 可以恢复普通 `SET` 命令
- `SETEX` 转换为 `SET + PEXPIREAT`
- 重启不会延长 TTL
- AOF 末尾半条命令会被截断
- AOF 中间非法 RESP 会启动失败且不修改原文件
- 多连接并发写入时，内存顺序与 AOF 顺序一致
- `RedisServer` 启动时重放、运行时追加、关闭时释放文件

除了自动化测试，我还使用 `redis-cli` 完成了真实验收。

第一次启动后执行：

```bash
SET name 李杰
SETEX session 2 temporary
```

AOF 中得到：

```text
SET name 李杰
SET session temporary
PEXPIREAT session <绝对毫秒时间戳>
```

停止并重启服务器后：

```text
GET name       -> 李杰
GET session    -> nil
TTL session    -> -2
```

这证明了三个结果：

- 中文内容按照 UTF-8 字节正确保存和恢复
- 永久数据可以在重启后恢复
- 已过期数据不会因为 AOF 重放重新获得 TTL

---

## 十、当前版本的取舍与不足

这个阶段没有直接照搬 ef-redis 的高性能 AOF 实现，而是先完成最小、清晰、可验证的同步版本。

当前方案的优点是：

- 结构清楚，写路径和恢复路径容易理解
- 每次写命令都会主动刷盘
- 能保证并发写入顺序与恢复结果一致
- 能处理 TTL 和文件尾部截断问题

当前方案也有明确不足：

- `channel.force()` 在 Netty EventLoop 上同步执行，会阻塞网络线程
- 写命令通过共享锁串行化，吞吐量有限
- AOF 会持续增长，尚未实现 Rewrite 压缩
- AOF 写入失败后，内存可能已经修改，尚未进入只读保护状态
- 启动时一次性读取整个 AOF，大文件会占用较多内存

下一步性能优化可以考虑：

```text
EventLoop执行命令
  -> 有序队列
  -> 独立AOF线程批量写入
  -> everysec或可配置刷盘策略
```

但在真正优化前，我需要先明确可靠性语义和压测基线，而不是为了“看起来高级”直接引入复杂的 RingBlockingQueue、mmap 和分段文件。

---

## 十一、这一阶段我真正学到了什么

第六阶段表面上是在“写文件”，实际上让我理解了持久化系统中的几个核心问题。

第一，AOF 不是简单打印日志。它必须能够被可靠解析和重新执行。

第二，相对时间不能直接跨越重启。所有 TTL 持久化都要考虑时间语义是否发生变化。

第三，内存执行顺序和日志顺序必须一致。只保证文件写入线程安全，不代表数据库恢复结果正确。

第四，异常恢复不能靠猜。末尾半包可以根据最后有效位置截断，中间损坏则应该保守失败。

第五，正确性和性能是分阶段建设的。当前同步实现不是最终答案，但它为后面的异步化提供了可验证的正确性基线。

现在项目的主链路已经变成：

```text
redis-cli
  -> TCP
  -> Netty Pipeline
  -> RESP解码
  -> CommandFactory
  -> Command
  -> RedisCore
  -> AOF追加
  -> RESP响应

服务器重启
  -> AOF读取
  -> RESP解码
  -> Command重放
  -> RedisCore恢复
```

---

## 结语

到这里，我的 Mini Redis 不再只是一个服务器运行期间有效的内存 Map，而是第一次具备了跨进程重启恢复数据的能力。

这一阶段让我从“命令可以执行”继续走到了“命令执行结果可以可靠恢复”。相比单纯增加几个 Redis 命令，AOF 更考验协议、文件 IO、时间语义、并发顺序和生命周期管理之间的协作。

下一阶段，我会继续围绕项目的可维护性和面试展示能力进行整理，并逐步评估 AOF 异步化、刷盘策略、Rewrite 以及更多 Redis 数据类型的实现方式。

如果你也在从零实现 Redis、学习 Netty 或准备 Java 后端面试，可以继续关注这个系列。后续我会继续用“设计思路 + 完整代码 + 测试验证 + 问题复盘”的方式，记录这个 Mini Redis 的演进过程。
