# 从 0 手敲一个 Mini Redis：第十阶段，参考官方 Redis 实现 AOF Rewrite

> **摘要：** 第六阶段我完成了 AOF 追加与重放，第八阶段实现了 `ALWAYS` 和 `EVERYSEC` 刷盘策略，但 AOF 仍会随写命令持续增长。第十阶段，我参考 Redis 官方思路，从当前内存状态生成最小恢复命令，将快照写入同目录临时文件，通过增量命令缓冲区保证 Rewrite 期间的写入不丢失，最后原子替换正式 AOF。我还实现了 `BGREWRITEAOF` 命令，并用 208 个测试完成全量回归。

**关键词：** Java、Redis、AOF Rewrite、BGREWRITEAOF、原子替换、并发一致性、RESP

---

## 一、为什么 AOF 只追加还不够

我的 AOF 保存的是写命令，而不是内存数据的直接序列化结果。例如客户端连续执行：

```text
SET name old
SET name twopair
SET name 李新数据
```

恢复最终状态其实只需要最后一条 `SET name 李新数据`。如果永远只追加，会带来三个问题：

1. AOF 文件不断增长，占用更多磁盘空间。
2. 服务重启时需要重放大量已被后续命令覆盖的历史。
3. 当前 `AofReplay` 会一次读入整个文件，过大的 AOF 还会增加堆内存压力。

因此，Rewrite 的核心不是“复制旧 AOF”，而是“从当前内存状态重新生成一份等价但更紧凑的 AOF”。

---

## 二、我从 Redis 官方实现中提取的原则

我没有照搬 Redis 的 C 代码，而是保留了对这个 Java 教学项目最重要的四条原则：

1. **根据内存状态生成恢复命令**，不复制旧 AOF 历史。
2. **集合命令分批输出**，单条命令最多写 64 个元素。
3. **先写同目录临时文件**，完整刷盘后再原子替换正式文件。
4. **Rewrite 期间继续接收写命令**，最后将增量命令追加到新 AOF。

官方 Redis 的后台 Rewrite 依赖 `fork` 与操作系统的写时复制（COW）。我的 Java 版本没有直接复制这个进程模型，而是用全局锁建立一致快照，再把文件写入交给独立后台线程。

---

## 三、把内存数据转换为最小命令

我新增了 `AofRewriteCommandBuilder`，它只负责将 `RedisCore` 当前状态转换为 `List<RespArray>` 恢复命令。

| 内存类型 | Rewrite 命令 |
|---|---|
| String | `SET key value` |
| List | 按队头到队尾输出 `RPUSH key ...` |
| Hash | `HMSET key field value ...` |
| Set | `SADD key member ...` |
| TTL | 数据命令之后输出 `PEXPIREAT key absoluteMillis` |

List、Hash 和 Set 每条命令最多携带 64 个元素。65 个 List 元素会拆成一条 64 元素的 `RPUSH` 和一条 1 元素的 `RPUSH`。

我还对 key、Hash 字段和 Set 成员做了排序。恢复正确性不依赖顺序，但稳定输出更容易测试、对比和排查问题。

### 为什么 TTL 必须用 PEXPIREAT

如果 Rewrite 仍写成 `EXPIRE session 60`，服务重启后数据会重新获得 60 秒，改变原本语义。因此我保存 `PEXPIREAT session absoluteMillis`，让数据无论何时重启都在同一个绝对时刻过期。

---

## 四、临时文件与原子替换

我将文件生命周期封装在 `AofRewriteFile` 中。它不直接修改正式 AOF，而是先在同目录创建临时文件：

```text
appendonly.aof
appendonly.aof.rewrite-xxxx.tmp
```

提交顺序是：

```text
写入快照命令
-> 写入 Rewrite 期间的增量命令
-> force
-> 关闭临时文件
-> ATOMIC_MOVE + REPLACE_EXISTING
```

原子替换保证其他线程看到的要么是完整旧文件，要么是完整新文件，不会看到替换到一半的 AOF。如果 Rewrite 失败或未提交就关闭，`close()` 会删除临时文件，正式 AOF 保持不变。

---

## 五、最难的部分：快照与增量命令的切换点

我在 `AofPersistence` 中增加了 Rewrite 状态和增量缓冲区：

```java
private volatile boolean rewriteInProgress;
private List<RespArray> rewriteBuffer = new ArrayList<>();
```

写命令仍先追加到当前正式 AOF，Rewrite 进行时再同时放入增量缓冲区：

```java
storage.appendAll(commands);
if (rewriteInProgress) {
	rewriteBuffer.addAll(commands);
}
```

这里最容易出错的地方是：Rewrite 提交后、快照真正建立前产生的命令，不能在快照和增量中各执行一次。例如 `LPUSH queue item` 如果重复重放，List 中就会出现两个 `item`。

我的处理时序是：

```text
rewriteAsync 标记 Rewrite 开始
-> 后台线程获取 AofPersistence 全局锁
-> 在锁内根据 RedisCore 建立快照
-> 清空快照建立前的 rewriteBuffer
-> 释放锁，新写命令开始进入 rewriteBuffer
-> 后台写快照文件
-> 再次获取锁，追加增量并原子切换
```

快照建立前的变化已经在内存快照里，所以要清空旧缓冲；快照建立后的变化才是真正增量。

---

## 六、为什么使用 volatile 与二次检查

我最初将 `rewriteAsync()` 整个声明为 `synchronized`。测试发现，当后台线程正在锁内建立快照时，第二个 `BGREWRITEAOF` 请求会阻塞在方法入口，而不是立即拒绝。

最终我使用“`volatile` 快速检查 + 锁内二次检查”：

```java
if (rewriteInProgress) {
	return false;
}

synchronized (this) {
	if (rewriteInProgress) {
		return false;
	}
	// 初始化增量缓冲区并提交后台任务
}
```

第一次检查避免 Netty EventLoop 等待长时间快照锁，第二次检查则防止两个线程同时通过快速检查后重复提交。

---

## 七、实现 BGREWRITEAOF 命令

`BGREWRITEAOF` 不修改普通 Redis 数据，但需要同时访问 `RedisCore` 和 `AofPersistence`。我没有让所有 `Command` 都依赖持久化对象，而是新增了一个窄接口：

```java
public interface AofManagementCommand extends Command {
	Resp handle(RedisCore redisCore, AofPersistence aofPersistence);
}
```

`CommandHandler` 只对这类命令注入 AOF 协调器。`BgRewriteAof` 没有实现 `WriteCommand`，因此管理命令本身不会被写进 AOF。

启动成功返回 `Background append only file rewriting started`；已有 Rewrite 进行时返回对应 RESP Error；未启用 AOF 时返回 `ERR AOF未启用`。

---

## 八、我如何测试 AOF Rewrite

我没有只断言方法返回了 `true`，而是把生成的 AOF 重放到一个全新 `RedisCoreImpl` 中，验证最终数据。本阶段覆盖了：

- String、List、Hash、Set 和 TTL 的恢复命令。
- 中文和二进制数据的字节安全。
- 65 个集合元素按 `64 + 1` 分批。
- Rewrite 前旧 AOF 保持不变，提交后原子替换。
- 取消 Rewrite 时临时文件被清理。
- Rewrite 期间新写命令不丢失。
- 重复 Rewrite 请求立即被拒绝。
- 关闭、空 `RedisCore` 和无真实文件路径的边界。
- `BGREWRITEAOF` 的命令工厂、参数校验与 Netty 端到端响应。

最终全量测试结果：

```text
Tests run: 208, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

端到端测试还验证了一个直观结果：同一个 key 的两条 `SET` 历史在 Rewrite 后只剩一条最终 `SET`，重放后中文值完整恢复。

---

## 九、一次真实 redis-cli Rewrite 验证

除了自动化测试，我还在独立临时目录启动了 `EVERYSEC` 服务，用官方 `redis-benchmark` 对 1000 个随机 key 执行 10000 次 64 字节 SET：

```bash
redis-benchmark -h 127.0.0.1 -p 6378 -n 10000 -c 50 -r 1000 -d 64 -t set -q
redis-cli -h 127.0.0.1 -p 6378 BGREWRITEAOF
```

| 项目 | 结果 |
|---|---:|
| Rewrite 前 AOF | 1,070,000 字节 |
| Rewrite 后 AOF | 106,893 字节 |
| 文件缩减 | 约 90% |
| `BGREWRITEAOF` 客户端返回耗时 | 约 0.01 秒 |

10000 条写历史最终只需要恢复约 1000 个 key 的当前值，因此文件从约 1.07 MB 降到约 0.107 MB。需要特别说明，0.01 秒只是命令提交后的客户端响应时间，不是后台 Rewrite 全部完成时间。

---

## 十、这个实现与官方 Redis 的差距

这一部分是面试时必须主动说清楚的。

### 1. 快照时的写请暂停

我在 `AofPersistence` 全局锁内扫描 `RedisCore` 并构建命令列表。数据量很大时，写请会在这段时间等待。官方 Redis 通过 `fork + COW` 建立进程级快照，主进程可以继续处理命令。

### 2. 增量缓冲的形式不同

我缓存的是 `List<RespArray>` Java 对象，便于学习和复用现有 RESP 编码。写压力很高时，对象数量和 GC 开销会增长，生产级实现更适合使用分块字节缓冲区。

### 3. 单文件模式与 manifest 模式

本项目使用“单个 AOF + 临时文件原子替换”的教学模型。较新 Redis 版本使用 base AOF、incremental AOF 和 manifest 组织多部分 AOF。

### 4. 重放仍然一次读入整个文件

Rewrite 能压缩文件，但 `AofReplay` 还没有改为固定缓冲区的流式解码。这是下一轮持久化优化的明确方向。

---

## 十一、面试时我会怎样介绍这一阶段

我会先给出一句话总结：

> 我参考 Redis AOF Rewrite 的核心思路，从当前内存状态生成最小 RESP 恢复命令，在后台写同目录临时文件，通过增量命令缓冲区保证并发写不丢失，最后刷盘并原子替换正式 AOF。

如果面试官继续追问，我会展开以下几点：

- 为什么 Rewrite 是根据内存状态生成命令，而不是过滤旧 AOF。
- 为什么 TTL 必须写为绝对 `PEXPIREAT`。
- 为什么临时文件要和目标文件处于同一目录。
- 快照完成的切换点如何避免 `LPUSH` 等命令重复重放。
- `volatile + synchronized` 二次检查如何避免重复请求阻塞 EventLoop。
- Java 教学版全局锁快照与 Redis `fork + COW` 的性能差距。

---

## 十二、总结

第十阶段让我第一次完整处理了一个“后台任务与前台并发写入必须无缝衔接”的持久化问题。我完成了四种数据类型与 TTL 的最小恢复命令、64 元素分批、临时文件原子替换、后台 Rewrite、增量缓冲、重复请求和 `BGREWRITEAOF` 交互。

更重要的是，我不只知道“AOF Rewrite 可以压缩文件”，而是能说清楚快照切换点、增量命令、原子替换和官方 `fork + COW` 之间的关系。

---

## 结尾引流

如果你也在学习 Java、Netty 或 Redis 底层原理，可以继续关注这个“从 0 手敲 Mini Redis”系列。后续我会继续完善 ZSet、AOF 流式重放和更接近生产环境的并发与性能设计。

仓库地址：`https://github.com/yupaiLy/twopair-miniredis`

如果这篇文章对你有帮助，欢迎点赞、收藏和关注，也欢迎一起交流 Redis 源码与 Java 后端面试问题。
