# 从 0 手敲一个 Mini Redis：第八阶段，我把 AOF 写入从 272 ops/s 优化到 11 万

> **摘要：** 第七阶段结束后，我的 Mini Redis 已支持 String、List、Hash、Set、TTL、AOF 恢复和 25 个命令，但默认 AOF 每执行一批写命令就同步 `force`，SET 吞吐只有约 272 ops/s。第八阶段，我拆分 AOF 的“追加”与“刷盘”职责，实现 `ALWAYS` 和 `EVERYSEC` 两种策略，并把每秒刷盘移到独立后台线程。最终，同版本、同环境下 EVERYSEC 的 SET 吞吐达到 112,359.55 ops/s，约提升 413 倍。本文记录这次优化的分析过程、实现方案、测试方法和持久性取舍。

**关键词：** Java、Netty、Redis、AOF、FileChannel、fsync、EventLoop、性能优化

---

## 一、为什么第八阶段先优化 AOF

第七阶段完成后，我先没有继续堆命令，而是用 `redis-benchmark` 测量现有系统。

优化前的核心结果如下：

| 配置 | SET ops/s | SET p50 |
|---|---:|---:|
| MiniRedis AOF 同步刷盘 | 约 272 | 约 146 ms |
| 官方 Redis AOF everysec | 约 204,082 | 约 0.14 ms |

问题不在 RESP 是否能解析，也不在 Netty 能不能接收并发连接，而在下面这条写路径：

```text
执行写命令
  -> 修改内存
  -> 编码RESP
  -> FileChannel.write
  -> FileChannel.force(false)
  -> 返回客户端
```

每个写请求都要等待一次物理刷盘。即使有 50 个客户端连接，它们最终也会在同一把 AOF 锁前排队：

```text
连接1：修改 -> write -> force -> 返回
连接2：                         修改 -> write -> force -> 返回
连接3：                                                  ...
```

单次刷盘如果需要约 3.7 ms，理论上每秒只能完成两百多次写入。增加 Netty Worker 或客户端连接数并不能突破磁盘延迟。

所以这一阶段的目标很明确：

> 保留 AOF 命令顺序和重启恢复能力，但不能再让 EVERYSEC 模式下的每条写命令都在 Netty EventLoop 上等待磁盘刷盘。

---

## 二、先分清 write 和 force

这一阶段最重要的知识点，是 `FileChannel.write()` 和 `FileChannel.force(false)` 并不是一回事。

### 2.1 write 做了什么

```java
channel.write(byteBuffer);
```

`write` 通常先把数据交给操作系统文件缓存。调用返回后，其他进程通常已经可以读取这些数据，但数据不一定已经进入持久化设备。

### 2.2 force 做了什么

```java
channel.force(false);
```

`force(false)` 要求把文件内容同步到持久化设备。它比普通写入慢得多，而且调用线程需要等待完成。

原来的 `AofFile.appendAll()` 把两件事绑定在一起：

```text
appendAll = RESP编码 + write + force
```

这样虽然容易保证持久性，但上层没有机会选择其他刷盘策略。因此我的第一步不是新建线程，而是先重新划分职责：

```text
AofFile.appendAll() -> 只追加
AofFile.force()     -> 只刷盘
```

只有先拆开这两个动作，`ALWAYS` 和 `EVERYSEC` 才能共享同一套文件写入代码。

---

## 三、为什么不能直接把所有 AOF 代码塞进 AofFile

拆分之后，`AofFile` 只负责底层文件操作：

```java
public final class AofFile implements AofStorage {
    public synchronized void appendAll(List<RespArray> commands) throws IOException {
        // RESP编码与FileChannel.write
    }

    public synchronized void force() throws IOException {
        channel.force(false);
    }
}
```

但是“什么时候刷盘”“是否需要后台线程”“关闭前怎么处理”不是文件对象本身应该决定的。

因此我增加了 `AofPersistence`：

```text
AofPersistence
  -> 持有 AofStorage
  -> 持有 AofFsyncPolicy
  -> 决定追加后是否立即force
  -> 管理EVERYSEC后台任务
  -> 管理关闭时最终刷盘
```

两个类的边界变成：

```text
AofFile        = 怎么写文件
AofPersistence = 什么时候写、什么时候刷、什么时候关
```

我还抽出了包级接口 `AofStorage`：

```java
interface AofStorage extends AutoCloseable {
    void appendAll(List<RespArray> commands) throws IOException;

    void force() throws IOException;
}
```

它的主要价值不是为了“接口越多越面向对象”，而是让测试可以使用记录操作顺序的替身，不依赖真实磁盘什么时候完成刷盘。

---

## 四、实现 ALWAYS 和 EVERYSEC 两种策略

我先定义刷盘策略枚举：

```java
public enum AofFsyncPolicy {
    ALWAYS,
    EVERYSEC
}
```

两种策略的语义如下：

| 策略 | 请求线程 | 后台线程 | 持久性取舍 |
|---|---|---|---|
| `ALWAYS` | append 后立即 force | 无 | 每批写命令立即刷盘，延迟高 |
| `EVERYSEC` | 只执行 append | 每秒 force | 吞吐高，断电时可能丢约 1 秒 |

`appendAll()` 的核心判断非常简单：

```java
storage.appendAll(commands);

if (policy == AofFsyncPolicy.ALWAYS) {
    storage.force();
}
```

EVERYSEC 使用单线程定时任务：

```java
fsyncExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
    Thread thread = new Thread(runnable, "redis-aof-fsync");
    thread.setDaemon(true);
    return thread;
});

fsyncTask = fsyncExecutor.scheduleWithFixedDelay(
        this::runBackgroundForce,
        1000L,
        1000L,
        TimeUnit.MILLISECONDS
);
```

我选择独立线程，是因为 Netty EventLoop 适合执行短时间、非阻塞的网络事件处理。磁盘 `force` 可能持续数毫秒甚至更久，如果放在 EventLoop 中，会让该线程上的其他连接一起等待。

这条原则可以概括为：

> EventLoop 可以执行普通任务，但不要让它执行不可控的长时间阻塞任务。

---

## 五、后台任务为什么必须自己捕获异常

定时任务里有一个容易忽略的问题：如果 `ScheduledExecutorService` 执行的周期任务直接抛出异常，后续调度可能被停止。

所以我没有让 `IOException` 直接逃出后台任务，而是保存失败状态：

```java
private synchronized void runBackgroundForce() {
    if (closed || !dirty) {
        return;
    }

    try {
        storage.force();
        dirty = false;
    } catch (IOException exception) {
        backgroundFailure = exception;
        LOGGER.error("AOF后台刷盘失败", exception);
    }
}
```

下一次写请求会检查这个状态：

```java
if (backgroundFailure != null) {
    throw new IOException("AOF后台刷盘已经失败", backgroundFailure);
}
```

这样后台失败不会悄悄消失。服务既会记录原始异常，也不会继续假装持久化完全正常。

当前实现仍有一个明确限制：命令执行顺序是“先修改内存，再追加 AOF”。如果 AOF 失败，内存不会自动回滚，客户端会收到持久化错误。这个问题涉及事务性回滚或写前日志，不适合在本阶段用几行代码掩盖。

---

## 六、为什么 append 和后台 force 使用同一把锁

`AofPersistence.appendAll()` 和 `runBackgroundForce()` 都使用 `synchronized`。

这保证后台线程不会在另一个线程写到一半时执行 `force`，也继续维持多个连接之间的 AOF 命令顺序。

`CommandHandler` 的写路径仍然是：

```java
synchronized (aofPersistence) {
    Resp response = command.handle(redisCore);
    List<RespArray> commands = writeCommand.toAofCommands(originalCommand, redisCore);
    aofPersistence.appendAll(commands);
    return response;
}
```

这把全局锁仍会串行化写命令，但 EVERYSEC 不再在锁中等待磁盘刷盘。锁内只剩内存修改、RESP 编码和文件追加，所以持锁时间从毫秒级大幅下降。

这个设计不是最终性能上限，但它保留了当前项目最重要的正确性约束：

```text
内存修改顺序 == AOF命令顺序
```

---

## 七、关闭服务时为什么还要最后 force 一次

EVERYSEC 的后台任务可能还没到下一次执行时间，服务就收到了正常关闭信号。

如果直接关闭文件，最后一秒的数据可能只停留在缓存里。因此 `close()` 会：

```text
标记closed
  -> 取消定时任务
  -> 关闭调度器
  -> 最后force一次
  -> 关闭AofStorage
```

关闭过程还会合并异常：如果最终 `force` 和 `close` 都失败，保留第一个异常，并把第二个放进 suppressed exceptions，而不是丢失其中一个错误。

此外，`close()` 是幂等的。Shutdown Hook、`finally` 和测试重复关闭时，只会真正释放一次资源。

---

## 八、把策略接入启动配置

底层支持 EVERYSEC 还不够，如果 `Main` 永远使用默认构造器，真实启动时仍然只能使用 ALWAYS。

我增加了 JVM 系统属性：

```text
miniredis.aof.fsync
```

启动 EVERYSEC：

```bash
java -Dminiredis.aof.fsync=everysec \
  -cp "target/classes:$(cat target/runtime-classpath.txt)" \
  cn.twopair.Main
```

未配置时继续使用 `ALWAYS`，避免本阶段偷偷改变原有持久性语义。配置值会统一去除空格并转换大小写，非法值在绑定端口之前直接报错。

这里的职责依然分开：

```text
Main             -> 从哪里读取配置
AofFsyncPolicy   -> 配置是否合法
RedisServer      -> 按确定的策略运行
AofPersistence   -> 执行策略
```

---

## 九、测试不能只依赖真实磁盘时间

真实磁盘测试会受到操作系统缓存、机器负载和文件系统实现影响，很容易出现偶发失败。

因此我的单元测试使用 `RecordingAofStorage` 记录操作顺序，重点验证三件事：

### 9.1 ALWAYS 立即刷盘

```text
append -> force
```

### 9.2 EVERYSEC 不在请求线程刷盘

追加后 `forceCount` 仍是 0，随后等待后台线程执行，并确认线程名称为 `redis-aof-fsync`。

### 9.3 正常关闭执行最终刷盘

```text
append -> force -> close
```

同时验证重复 `close()` 不会重复关闭底层文件。

入口测试还覆盖了：

- 未配置时使用 `ALWAYS`；
- `everysec` 可以忽略大小写解析；
- 非法配置在启动前抛出清晰异常。

我还使用 `dirty` 标记记录上次成功刷盘后是否出现了新追加。空闲期间后台任务不会无意义地调用 `force`，追加成功后设为 `true`，刷盘成功后才恢复为 `false`，刷盘失败则继续保留待刷状态。

本阶段完成后，全项目共有 **168 个测试，全部通过**。

---

## 十、性能结果：SET 提升约 413 倍

为了避免跨版本比较，我使用当前代码做了同环境 A/B 测试：

```text
redis-benchmark -n 10000 -c 50 -d 64 -t set,get
```

两个服务都使用：

- 同一份当前代码；
- 相同的 WARN 日志级别；
- 全新的临时 AOF 文件；
- localhost 回环网络；
- 充分预热后的正式数据。

结果如下：

| 策略 | SET ops/s | SET p50 | GET ops/s | GET p50 |
|---|---:|---:|---:|---:|
| `ALWAYS` | 272.11 | 146.047 ms | 133,333.33 | 0.183 ms |
| `EVERYSEC`（三轮中位数） | 112,359.55 | 0.431 ms | 178,571.42 | 0.151 ms |

SET 吞吐提升约：

```text
112359.55 / 272.11 ≈ 413
```

这不是因为文件写入消失了，而是每条命令不再独占一次 `force`。多个写请求先快速进入操作系统缓存，再由后台线程统一承担刷盘成本。

与官方 Redis 7.2.6 的 EVERYSEC 约 204,082 SET ops/s 相比，我的实现达到约 55%。剩余差距主要来自：

- 写路径仍有全局锁；
- 每条命令单独 RESP 编码和 `FileChannel.write`；
- Command、Resp 和 ByteBuf 等对象分配；
- 每请求 traceId 和 MDC 操作；
- 尚未实现批量追加、组提交和响应 flush 合并。

对于教学项目，我更看重的不是“跑赢官方”，而是完成了一次可以解释、可以验证、可以复现的性能优化闭环。

---

## 十一、kill -9 为什么不能证明断电只丢一秒

我还做了一次进程强杀实验：

```text
写入第一条命令
  -> 等待2秒，确保后台force
  -> 写入第二条命令
  -> 立即kill -9 JVM
  -> 重启并重放AOF
```

重启后，两条命令都恢复成功，AOF 也没有损坏。

一开始很容易得出错误结论：EVERYSEC 好像不会丢数据。

实际上，`kill -9` 只杀死 JVM，不会清空操作系统页缓存。第二条命令虽然还没有经过 `force`，但 `FileChannel.write` 已经把它交给内核，所以重启进程仍然能够读取。

EVERYSEC 的一秒风险主要针对：

- 机器突然断电；
- 操作系统内核崩溃；
- 存储设备或虚拟磁盘故障。

这些场景不能仅靠 `kill -9` 准确模拟，需要虚拟机断电、文件系统故障注入或专门的存储测试环境。

这次实验让我认识到：

> 进程存活、操作系统缓存和持久化设备是三个不同层次。测试进程崩溃，不等于测试数据已经落盘。

---

## 十二、这一阶段仍然有哪些限制

EVERYSEC 已完成，但 AOF 性能还没有走到终点：

1. `ALWAYS` 仍然每批命令独占一次 `force`，尚未实现组提交；
2. 写路径全局锁仍会串行化不同 key 的写命令；
3. AOF 只增不减，尚未实现 Rewrite；
4. `AofReplay` 仍使用 `Files.readAllBytes`，大文件可能占用大量堆内存；
5. 后台刷盘失败后只能拒绝后续持久化，内存修改不会回滚；
6. 暂不支持类似 Redis `appendfsync no` 的策略。

下一步可以继续实现 ALWAYS 组提交，或者转向 Pipeline 响应合并、AOF 流式重放和 Rewrite。

---

## 十三、面试时我会怎么总结这一阶段

如果面试官问“你做过什么性能优化”，我会这样回答：

> 我先用 redis-benchmark 定位到 MiniRedis 的 SET 只有约 272 ops/s，原因是每条写命令都在 Netty EventLoop 上同步执行 FileChannel.force，并且被全局锁串行化。随后我把 AOF 的 append 和 force 拆开，引入 AofPersistence 协调 ALWAYS 与 EVERYSEC 两种策略，让 EVERYSEC 由独立线程每秒刷盘，正常关闭时再最终刷盘。测试上使用 AofStorage 替身验证操作顺序和线程语义，最后用同版本 A/B 测试把 SET 提升到约 11.2 万 ops/s，提升约 413 倍。代价是断电时可能丢失约一秒数据，而 kill -9 不能准确模拟这个窗口，因为操作系统页缓存仍然存在。

这个回答包含了完整的工程链路：

```text
指标发现问题
  -> 定位阻塞点
  -> 划分职责
  -> 实现并发方案
  -> 自动化测试
  -> 基准验证
  -> 说明一致性与持久性代价
```

---

## 结尾

第八阶段让我真正体会到，性能优化不是把代码改成“异步”三个字就结束了。只有先理解操作系统缓存、刷盘语义、EventLoop 线程模型和关闭生命周期，才能在提升吞吐的同时知道自己牺牲了什么。

下一阶段，我会继续沿着性能报告中的瓶颈推进，重点研究 ALWAYS 组提交、Pipeline 批量响应与 AOF 流式重放。如果你也在从零实现 Redis、学习 Netty 或准备 Java 后端面试，欢迎继续关注后续源码拆解，也欢迎在评论区交流你的实现思路。
