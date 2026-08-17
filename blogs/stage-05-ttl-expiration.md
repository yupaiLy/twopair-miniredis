# 从 0 手敲一个 Mini Redis：第五阶段，我实现了 TTL、SETEX 与主动过期清理

## 摘要

完成 Netty 服务端后，我的 Mini Redis 已经能够接受 `redis-cli` 请求，并执行 `PING`、`SET`、`GET` 等基础命令。第五阶段继续补齐 Redis 中非常重要的一块能力：键过期。

这一阶段我实现了 `EXPIRE`、`TTL` 和 `SETEX`，把 RESP 整数升级为 `long`，通过可注入时间源实现了不依赖 `Thread.sleep` 的确定性测试，并组合使用惰性删除和后台主动清理回收过期数据。

最终形成的过期链路是：

```text
EXPIRE / SETEX
  -> 保存绝对过期时间
  -> GET / TTL 惰性检查
  -> 后台线程主动扫描
  -> 条件删除过期数据
```

这一阶段完成后，项目全量 51 个测试全部通过。

---

## 一、为什么 TTL 不能只加一个 timeout 字段

我的数据对象一开始已经预留了 `timeout`：

```java
long timeout();

void setTimeout(long timeout);
```

但有字段不代表真正实现了过期功能。完整的 TTL 至少要回答这些问题：

- 过期时间保存相对秒数还是绝对时间戳？
- `GET` 读到过期数据时怎么办？
- 从未被访问的冷 key 怎么释放？
- 并发写入新值时，清理线程会不会误删新值？
- `TTL` 对不存在、永久存在和即将过期的 key 分别返回什么？
- `SETEX` 如何保证写入 value 和设置过期时间不会出现中间状态？

所以这一阶段不是简单增加一个字段，而是要建立完整的过期语义和生命周期。

---

## 二、保存绝对过期时间

我选择在 `RedisData.timeout` 中保存绝对毫秒时间戳：

```text
expireAt = 当前时间 + seconds * 1000
```

例如当前时间为 `1000ms`，执行：

```bash
EXPIRE name 10
```

保存的不是数字 `10`，而是：

```text
11000ms
```

永久存在的数据使用 `-1` 表示。

这样每次检查时只需要判断：

```java
timeout != -1L && timeout <= now
```

当当前时间到达过期时间本身时，key 就应该被视为已经过期。

---

## 三、注入时间源，让 TTL 测试不再依赖 sleep

如果业务代码直接到处调用：

```java
System.currentTimeMillis()
```

测试过期功能通常只能真的等待时间流逝。这样既慢，也容易因为线程调度产生不稳定结果。

因此我给 `RedisCoreImpl` 注入了 `LongSupplier`：

```java
private final LongSupplier currentTimeMillis;

public RedisCoreImpl() {
    this(System::currentTimeMillis);
}

public RedisCoreImpl(LongSupplier currentTimeMillis) {
    this.currentTimeMillis = Objects.requireNonNull(
            currentTimeMillis,
            "时间源不能为空"
    );
}
```

生产环境仍然使用系统时间，测试环境则可以传入 `AtomicLong`：

```java
AtomicLong currentTime = new AtomicLong(1000L);
RedisCore redisCore = new RedisCoreImpl(currentTime::get);

currentTime.set(11000L);
```

这样测试可以瞬间推进十秒，不需要真正等待十秒。

这也是我在这一阶段学到的重要测试设计：如果时间会影响业务行为，就应该把时间当成依赖，而不是写死在业务代码中。

---

## 四、GET 中的惰性删除

第一种过期策略是惰性删除：访问 key 时再判断它是否过期。

核心逻辑是：

```java
RedisData redisData = map.get(key);
long timeout = redisData.timeout();
long now = currentTimeMillis.getAsLong();

if (timeout == -1L || timeout > now) {
    return redisData;
}

if (map.remove(key, redisData)) {
    return null;
}
```

这里没有使用：

```java
map.remove(key);
```

而是使用：

```java
map.remove(key, redisData);
```

原因是读取旧值后，其他线程可能已经对相同 key 执行了 `SET`。如果只按 key 删除，当前线程可能把刚写入的新值误删。

条件删除表达的语义是：只有 Map 中仍然保存着我刚才读到的旧对象，删除才成功。

---

## 五、实现 EXPIRE 与 TTL

我在 `RedisCore` 中加入了两个能力：

```java
boolean expire(BytesWrapper key, long seconds);

long ttl(BytesWrapper key);
```

### EXPIRE 的语义

`expire()` 对存在的 key 设置绝对过期时间并返回 `true`，key 不存在时返回 `false`。

非正数过期时间表示立即删除：

```java
EXPIRE name 0
EXPIRE name -1
```

设置正数 TTL 时，我使用 `computeIfPresent()` 针对当前 key 原子更新：

```java
map.computeIfPresent(key, (currentKey, redisData) -> {
    long expireAt = now + seconds * 1000L;
    redisData.setTimeout(expireAt);
    return redisData;
});
```

同时使用 `Math.multiplyExact()` 和 `Math.addExact()` 防止超大过期时间发生静默溢出。

### TTL 的返回值

我实现了与 Redis 一致的三个核心返回值：

```text
-2：key 不存在或已经过期
-1：key 永久存在，没有设置过期时间
>=0：剩余存活秒数
```

只剩几毫秒但尚未过期时，整数秒结果可能是 `0`，这时 key 仍然存在。

---

## 六、RESP 整数为什么要升级成 long

TTL 和过期时间可能超过 Java `int` 的范围，因此这一阶段我把 RESP Integer 的内部值升级成了 `long`。

RESP 协议中的整数仍然是文本形式：

```text
:10\r\n
```

变化发生在 Java 对象层：

```java
private final long value;
```

解码数字时也使用手动的 `long` 累积，并检查非法字符、半包、CRLF 和数值溢出。

这让我进一步理解了一个边界：协议格式没有变化，但程序内部的数据类型仍然必须覆盖业务可能出现的范围。

---

## 七、SETEX 为什么必须是原子操作

`SETEX` 的语法是：

```bash
SETEX name 10 twopair
```

它要求写入 value 的同时设置 10 秒过期时间。

最直观的实现是：

```java
redisCore.put(key, value);
redisCore.expire(key, seconds);
```

但这两个操作之间存在窗口。其他线程可能观察到一个已经写入、却暂时永久存在的数据。

所以我在核心层增加了：

```java
void putWithExpiration(
        BytesWrapper key,
        RedisData value,
        long seconds
);
```

实现时先完成对象初始化，再发布到 Map：

```java
value.setTimeout(expireAt);
map.put(key, value);
```

其他线程只可能看到旧对象，或者看到已经带有完整过期时间的新对象。

`SetEx.handle()` 只需要调用这个核心方法：

```java
RedisString redisString = new RedisString(value);
redisCore.putWithExpiration(key, redisString, seconds);
return new SimpleString("OK");
```

这一设计把原子写入语义放在存储核心，而不是让命令层自己拼接多个操作。

---

## 八、为什么只有惰性删除还不够

惰性删除只会处理被访问的 key。

如果一个 key 已经过期，但之后再也没有执行过 `GET` 或 `TTL`，它会一直留在 Map 中占用内存。

因此我又加入了主动清理：

```java
int removeExpired();
```

实现会扫描 `ConcurrentHashMap`，跳过永久数据和尚未过期的数据，并通过条件删除回收过期对象：

```java
for (Map.Entry<BytesWrapper, RedisData> entry : map.entrySet()) {
    RedisData redisData = entry.getValue();
    long timeout = redisData.timeout();

    if (timeout == -1L || timeout > now) {
        continue;
    }

    if (map.remove(entry.getKey(), redisData)) {
        removedCount++;
    }
}
```

`ConcurrentHashMap` 的弱一致性迭代允许扫描期间继续并发读写，而 `remove(key, oldValue)` 保证清理线程不会误删刚写入的新值。

---

## 九、清理任务为什么不能放在 Netty EventLoop

Netty EventLoop 可以执行任务，但不应该执行长时间阻塞或耗时任务。

全量扫描 Map 虽然不是磁盘 I/O，但数据量增大后会成为耗时 CPU 任务。如果把它放到 Worker EventLoop，扫描期间这个线程负责的网络连接都无法及时处理读写事件。

因此我创建了独立的单线程定时执行器：

```java
Executors.newSingleThreadScheduledExecutor(runnable -> {
    Thread thread = new Thread(
            runnable,
            "redis-expiration-cleaner"
    );
    thread.setDaemon(true);
    return thread;
});
```

然后使用固定延迟调度：

```java
expirationExecutor.scheduleWithFixedDelay(
        redisCore::removeExpired,
        cleanupIntervalMillis,
        cleanupIntervalMillis,
        TimeUnit.MILLISECONDS
);
```

我选择 `scheduleWithFixedDelay()`，是因为它会在本次任务结束后再等待一个间隔，避免清理速度跟不上时不断堆积任务。

服务关闭时还必须同步释放后台任务：

```java
expirationCleanupTask.cancel(false);
expirationExecutor.shutdownNow();
```

这让清理线程和 Redis 服务拥有一致的生命周期。

---

## 十、测试覆盖

这一阶段的测试覆盖了：

- 可控时间源与惰性过期
- `EXPIRE` 正常设置、立即删除和 key 不存在
- `TTL` 的 `-2`、`-1`、`0` 与正数返回值
- RESP `long` 整数编解码
- `SETEX` 正常写入和过期
- `SETEX` 参数数量、类型、NIL、非法数字和非正数校验
- `CommandFactory` 注册 SETEX
- `CommandHandler` 完整 SETEX 网络处理链路
- 主动清理只删除真正过期的数据
- 清理任务运行在独立守护线程
- 服务关闭后停止执行清理任务

最终全量测试结果：

```text
Tests run: 51, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

---

## 十一、当前实现与真实 Redis 的差距

当前 Mini Redis 每次主动清理都会全量扫描 Map。这种实现清晰、容易验证，适合作为学习项目，但数据量很大时扫描成本是 `O(n)`。

真实 Redis 的过期策略更加精细，会结合惰性删除、主动抽样、时间预算和动态调整，避免一次清理长时间占用 CPU。

如果继续优化，我会考虑：

- 每次只抽样部分设置了 TTL 的 key
- 给单轮清理设置最大时间预算
- 单独维护带过期时间的 key 集合
- 记录每轮扫描数量、删除数量和耗时
- 防止定时任务异常后停止后续调度

我认为学习项目不需要一开始复制所有生产级细节，但需要知道当前简化在哪里，以及下一步如何演进。

---

## 十二、这一阶段我学到的东西

第五阶段最大的收获，不只是多实现了三个命令，而是把时间、并发和生命周期放进了同一套设计中：

```text
时间源注入 -> 测试可控
条件删除 -> 并发安全
原子SETEX -> 不暴露中间状态
惰性删除 -> 访问时保证语义正确
主动清理 -> 回收冷数据
独立线程 -> 不阻塞Netty EventLoop
统一关闭 -> 不泄漏后台资源
```

这些问题也是面试中比“会写一个 Map”更有价值的部分。

---

## 结尾引流

这是我从 0 手敲 Mini Redis 系列的第五阶段总结。

这一阶段完成了 TTL 过期体系，并把命令语义、并发安全、定时任务和 Netty 生命周期连接起来。后续我会继续完善持久化、更多数据结构或内存淘汰能力，并继续记录每一个设计取舍。

如果你也在学习 Redis、Netty 或 Java 并发，可以继续关注这个系列。下一篇我会从新的阶段出发，继续把这个 Mini Redis 从“能够运行”推进到“更接近一个完整系统”。
