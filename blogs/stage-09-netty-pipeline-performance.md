# 从 0 手敲一个 Mini Redis：第九阶段，用 Netty 合并 Flush 让 Pipeline GET 突破 200 万 ops/s

> **摘要：** 第八阶段完成 AOF 刷盘优化后，我继续排查 Mini Redis 在 Pipeline 场景中的网络开销。我发现每条命令都调用一次 `writeAndFlush()`，会把一次 TCP 读取批次中的多个响应反复刷向底层。第九阶段，我学习了 Netty 的 `write`、`flush`、`channelReadComplete` 与异常传播机制，引入 `FlushConsolidationHandler` 合并刷出事件，并用 `EmbeddedChannel` 锁定 256 次阈值、错误响应与异常关闭边界。最终，在相同环境下，`P16` 的 SET 吞吐从 152,439 提升到 297,619 ops/s，GET 从 666,667 提升到 2,083,333 ops/s。

**关键词：** Java、Netty、Redis、Pipeline、FlushConsolidationHandler、RESP、性能优化

---

## 一、为什么还要优化网络刷出

第八阶段结束后，我的 Mini Redis 已经把 AOF `EVERYSEC` 的磁盘同步移到后台线程，写命令不再被每次 `force()` 阻塞。但当客户端使用 Redis Pipeline 连续发送命令时，服务端仍然是每处理一条命令就执行一次：

```java
ctx.writeAndFlush(response);
```

这段代码没有功能问题，却可能产生额外系统调用。

Redis Pipeline 的核心是客户端连续发送多条命令，再批量读取响应。假设一次 Socket 读取已经拿到 16 条命令，Netty 会在同一读取批次内多次触发 `channelRead`，最后触发一次 `channelReadComplete`。如果每条响应都立即 `flush`，就没有充分利用这个天然的批次边界。

这一阶段的目标不是简单地把 `writeAndFlush()` 全部改成 `write()`，而是在保证响应及时性、错误处理和连接关闭语义的前提下，安全地合并底层 `flush`。

---

## 二、我先补齐了哪些 Netty 知识

### 1. write 与 flush 不是一回事

`write()` 负责把出站消息沿 Pipeline 传播，并最终进入 Channel 的出站缓冲区；`flush()` 负责推动已经写入的消息向底层传输层发送。

因此：

```text
writeAndFlush(message) = write(message) + flush()
```

如果连续处理 16 条命令时调用 16 次 `writeAndFlush()`，就会产生 16 个 flush 事件。即使操作系统最后可能合并部分网络包，Netty 仍然要重复处理这些事件。

### 2. channelReadComplete 是天然批次边界

一次底层读取可能解码出多条 RESP 命令，事件顺序大致如下：

```text
channelRead(command1)
channelRead(command2)
...
channelRead(commandN)
channelReadComplete()
```

所以，同一读取批次中的响应可以先写入，等 `channelReadComplete()` 再统一刷出。

### 3. 不能无限等待批次结束

如果一次读取解析出了大量命令，一直等到最后才刷出，会让已完成的响应长期积压。因此 Netty 的 `FlushConsolidationHandler` 默认使用 256 作为阈值：

```text
未达到256次：在channelReadComplete时统一flush
达到256次：立即flush一次并重新计数
```

这是一种吞吐与延迟之间的平衡。

### 4. 异常和关闭也必须触发刷出

如果前面的正常命令已经产生响应，后面的字节却发生协议错误，那么服务端不能直接关闭连接，否则前面的响应可能仍停留在缓冲区中。

正确顺序应当是：

```text
刷出已经完成的正常响应
写出协议错误响应
等待错误响应写出完成
关闭连接
```

---

## 三、为什么抽出 RedisPipeline

原本 `RedisServer` 直接在 `ChannelInitializer` 中组装 Handler。为了让生产服务和 `EmbeddedChannel` 测试使用完全相同的处理链，我新增了 `RedisPipeline`：

```java
public static void configure(ChannelPipeline pipeline, RedisCore redisCore, AofPersistence aofPersistence) {
	Objects.requireNonNull(pipeline, "ChannelPipeline不能为空");
	Objects.requireNonNull(redisCore, "RedisCore不能为空");
	pipeline.addLast(new RespDecoder()).addLast(new RespEncoder()).addLast(new FlushConsolidationHandler()).addLast(new CommandHandler(redisCore, aofPersistence));
}
```

每个连接的处理链现在统一为：

```text
RespDecoder
-> RespEncoder
-> FlushConsolidationHandler
-> CommandHandler
```

这里有一个容易混淆的点：入站事件从前向后传播，出站事件从当前 Handler 向前传播。`CommandHandler` 调用 `writeAndFlush()` 后，出站事件会先经过 `FlushConsolidationHandler`，再经过 `RespEncoder`，最终到达底层 Channel。

`RedisServer` 因而只负责调用：

```java
RedisPipeline.configure(channel.pipeline(), redisCore, aofPersistence);
```

这样既减少了服务器启动代码的细节，也避免测试和生产配置不一致。

---

## 四、我怎样证明 Flush 真的被合并了

我没有只看吞吐量，而是先写可重复的行为测试。

测试中在 Pipeline 最前面放置一个 `FlushCountingHandler`，每当 flush 真正传播到底层时就累加计数：

```java
private static final class FlushCountingHandler extends ChannelOutboundHandlerAdapter {
	private int flushCount;

	@Override
	public void flush(ChannelHandlerContext ctx) throws Exception {
		flushCount++;
		ctx.flush();
	}
}
```

### 测试一：两条命令只刷一次

同一次 `writeInbound()` 传入两条 `PING`：

```text
输入：PING、PING
输出：PONG、PONG
底层flush次数：1
```

测试最初失败时，两个响应对应两次 flush；接入 `FlushConsolidationHandler` 后变为一次，完成 RED 到 GREEN。

### 测试二：超过256次阈值

一次送入 300 条 `PING`：

```text
前256条响应：达到阈值，flush一次
剩余44条响应：channelReadComplete时再flush一次
总响应数：300
总flush次数：2
```

这个用例把默认阈值从“文档描述”变成了项目中可重复验证的行为。

### 测试三：业务错误不关闭连接

一次发送 `PING` 和未知命令 `UNKNOWN`：

```text
+PONG\r\n
-ERR 不支持的命令: UNKNOWN\r\n
```

两条响应顺序正确，只触发一次底层 flush，并且连接保持打开。因为未知命令是业务错误，不代表 RESP 字节流已经损坏。

### 测试四：协议异常前先刷出正常响应

我用一块真实 ByteBuf 发送：

```text
*1\r\n$4\r\nPING\r\n?\r\n
```

前半段是合法 `PING`，后面的 `?` 不是合法 RESP 类型。预期结果为：

```text
先返回PONG
再返回Protocol error
底层flush两次
最后关闭连接
```

---

## 五、边界测试还发现了一个真实 Bug

协议异常测试第一次运行时失败了：

```text
expected flush count: 2
actual flush count: 3
```

日志里出现了两次协议异常。第一次解析到了非法字符 `?`，但缓冲区中还残留 `\r\n`。连接关闭时，`ByteToMessageDecoder` 会执行最后一次解码，残留的 `\r` 又被当成 RESP 类型首字节，于是产生第二次异常。

我的修复方式是在确认协议已经损坏时，丢弃缓冲区中的剩余字节，再把异常继续抛给 Pipeline：

```java
try {
	Resp resp = Resp.tryDecode(in);
	if (resp != null) {
		out.add(resp);
	}
} catch (RuntimeException e) {
	in.skipBytes(in.readableBytes());
	throw e;
}
```

这里不能吞掉异常，因为后续 `CommandHandler.exceptionCaught()` 仍然需要返回协议错误并关闭连接。我的改动只负责防止关闭阶段重复解析已经判定无效的残留字节。

---

## 六、完整测试结果

完成边界修复后，我运行了全量测试：

```text
Tests run: 172
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

覆盖范围包括：

- RESP 编解码与 TCP 半包
- Pipeline 刷出次数和256次阈值
- 错误响应与异常关闭
- String、List、Hash、Set、TTL 命令
- AOF 追加、刷盘与恢复
- Netty 服务启动、连接共享和关闭生命周期

---

## 七、性能测试方法

我没有直接拿当前结果和之前随手记录的数据比较，而是从 Stage 8 的 Tag 建立临时 Git Worktree，用相同机器、相同 JVM、相同参数进行 A/B 测试。

统一条件如下：

```text
请求数：100000
并发连接：50
Pipeline深度：16
Value大小：64字节
AOF策略：EVERYSEC
日志级别：WARN
```

测试命令：

```bash
redis-benchmark -h 127.0.0.1 -p 6378 -n 100000 -c 50 -P 16 -d 64 -t set,get -q
```

为了避免 JIT 编译和类加载影响，我先执行多轮热身，再连续测量三轮并取中位数。

---

## 八、最终性能结果

| 版本 | P16 SET ops/s | SET p50 | P16 GET ops/s | GET p50 |
|---|---:|---:|---:|---:|
| Stage 8，每条响应独立flush | 152,439.02 | 2.351 ms | 666,666.62 | 0.687 ms |
| Stage 9，批次合并flush | 297,619.06 | 2.327 ms | 2,083,333.38 | 0.215 ms |

SET 吞吐提升约：

```text
297619.06 / 152439.02 ≈ 1.952
```

也就是提升约 **95.2%**。

GET 吞吐提升约：

```text
2083333.38 / 666666.62 ≈ 3.125
```

也就是提升约 **212.5%**。

在 `P=1` 的首轮控制测试中，两版 SET 和 GET 基本持平；进入 `P=16` 后差距才明显放大。这组对照说明，本次收益主要来自同一读取批次中的 flush 合并，而不是 AOF、数据结构或命令实现变化。

GET 的提升高于 SET，是因为 SET 仍然需要修改内存并追加 AOF，存在额外工作；GET 的处理链更短，减少 flush 事件后的收益更容易直接反映在吞吐上。

---

## 九、这次优化有哪些代价

合并 flush 并不是免费午餐，它本质上是在很短的时间窗口内用少量延迟换取更高吞吐。

当前策略通过两个边界控制风险：

```text
正常批次：channelReadComplete时刷出
超大批次：累计256次时提前刷出
异常或关闭：先刷出待处理响应
```

因此，普通非 Pipeline 请求不会无限等待，超大批次也不会无限积压。

另一个需要注意的点是，`FlushConsolidationHandler` 优化的是 Netty flush 事件数量，不保证每次 flush 一定对应一个 TCP 包。最终数据如何分段还会受到 Netty 缓冲区、操作系统 TCP 栈、Nagle 算法和网卡等因素影响。

---

## 十、面试时我会怎么介绍

如果面试官问我做过哪些 Netty 性能优化，我会这样回答：

> 我的 Mini Redis 支持 Redis Pipeline，但最初每处理一条命令都调用一次 writeAndFlush，导致同一 Socket 读取批次产生大量重复 flush。我先通过 EmbeddedChannel 和自定义 OutboundHandler 统计真实 flush 次数，再引入 Netty 的 FlushConsolidationHandler，在 channelReadComplete 或累计256次时统一刷出。测试覆盖正常批次、阈值、业务错误和协议异常关闭，还发现并修复了 ByteToMessageDecoder 在关闭时重复解析残留字节的问题。最后用 Stage 8 Tag 做同环境 A/B 测试，P16 的 SET 从约15.2万提升到29.8万 ops/s，GET 从约66.7万提升到208.3万 ops/s。

这个回答体现的不只是“我会使用一个 Netty Handler”，而是完整的工程过程：

```text
理解事件模型
-> 建立可观测测试
-> RED/GREEN实现
-> 补齐异常边界
-> 全量回归
-> 同环境A/B验证
```

---

## 结尾

第九阶段让我更深刻地理解了 Netty 的事件传播和刷出语义。一次看似简单的 `writeAndFlush()`，放到高吞吐 Pipeline 场景中，就可能成为大量重复工作的来源；而一次异常关闭，也必须考虑缓冲区中已经完成但尚未刷出的响应。

性能优化不能只看最终数字。只有把原理、测试、异常边界和基准方法串起来，提升结果才真正可信。

下一阶段，我会继续完善 Mini Redis 的性能与工程能力，包括评估 AOF 组提交、流式恢复、背压与更完整的压测指标。如果你也在从零实现 Redis、学习 Java 与 Netty，或者正在准备后端面试，欢迎关注后续源码拆解，也欢迎在评论区交流你的实现方案。
