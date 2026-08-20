# 从 0 手敲一个 Mini Redis：第七阶段，我实现了 List、Hash、Set 与游标扫描

> **摘要：** 前六个阶段完成了内存存储、RESP 协议、命令分发、Netty 服务端、TTL 和 AOF 持久化。第七阶段，我把项目从只支持 String 的 KV 存储，扩展成支持 String、List、Hash、Set 四种数结构的 Mini Redis。这一阶段不只是增加命令，还需要统一处理多类型存储、并发原子性、WRONGTYPE、空集合删除、AOF 写入和 SCAN 弱一致性语义。本文记录我的实现过程、设计取舍与性能反思。

**关键词：** Java、Netty、Redis、ConcurrentHashMap、List、Hash、Set、SCAN、AOF

---

## 一、第七阶段要解决什么

第六阶段结束时，项目已经可以用 `redis-cli` 连接，也能执行 `SET`、`GET`、`SETEX`、`EXPIRE` 等命令。

但那时的存储模型本质上还是：

```text
key -> RedisString
```

真正的 Redis 不只是字符串 KV 存储。List、Hash 和 Set 会引入一组新问题：

1. 同一个 key 可以保存不同类型，命令怎么判断类型？
2. 多个 Netty Worker 同时修改同一个集合，怎么避免丢数据？
3. `HDEL` 删掉最后一个 field、`SREM` 删掉最后一个 member 后，key 还应该存在吗？
4. 读写集合时如果 key 已过期，应该按不存在处理，还是返回 WRONGTYPE？
5. `SCAN/HSCAN/SSCAN` 的游标、MATCH 和 COUNT 怎么设计？
6. 新增的写命令怎么自动进入现有 AOF 链路？

所以这一阶段的目标不是“把几个 API 写出来”，而是让多数据结构在现有的 RESP、Netty、TTL 和 AOF 体系中正确运行。

---

## 二、本阶段最终完成了什么

第七阶段完成后，项目已经支持 25 个命令。其中本阶段新增的主要命令如下。

| 分类 | 命令 | 核心语义 |
|---|---|---|
| List | `LPUSH` | 向头部压入一个或多个元素 |
| List | `LPOP` | 弹出头部元素，空列表删除 key |
| List | `LLEN` | 返回列表长度 |
| List | `LRANGE` | 闭区间查询，支持负数下标 |
| Hash | `HSET` | 批量新增或覆盖 field-value |
| Hash | `HGET` | 读取指定 field |
| Hash | `HDEL` | 删除 field，空 Hash 删除 key |
| Hash | `HLEN` | 返回 field 数量 |
| Hash | `HSCAN` | 游标分页扫描 field-value |
| Set | `SADD` | 添加一个或多个唯一成员 |
| Set | `SREM` | 删除成员，空 Set 删除 key |
| Set | `SISMEMBER` | 判断成员是否存在 |
| Set | `SCARD` | 返回成员数量 |
| Set | `SSCAN` | 游标分页扫描成员 |
| Key | `TYPE` | 查询 key 的数据类型 |
| Key | `DEL` | 批量删除 key |
| Key | `SCAN` | 游标扫描全局 key |
| Connection | `SELECT 0` | 兼容只使用默认库的客户端 |

除了命令本身，我还补齐了：

- 统一的 `WrongTypeException`；
- List、Hash、Set 的值对象；
- 集合命令与 AOF 写入的自动衔接；
- 命令级 `traceId` 和滚动日志；
- Redis GUI 工具常用的 `SELECT`、`SCAN` 和 `TYPE` 兼容能力；
- 数据结构、核心存储、命令、Netty 集成与 AOF 的分层测试。

---

## 三、先扩展值对象，而不是直接写命令

我没有一上来就写 `LPUSH` 或 `HSET`，而是先实现三个数据对象：

```text
RedisData
├── RedisString
├── RedisList
├── RedisHash
└── RedisSet
```

这些对象都实现 `RedisData`，因此可以继续保存统一的绝对过期时间：

```java
public interface RedisData {
    long timeout();

    void setTimeout(long timeout);
}
```

这个设计很重要。TTL 属于 key，而不是 String 独有的能力。无论 key 中存的是字符串、列表、Hash 还是 Set，都应该能被 `EXPIRE` 设置过期时间。

### 3.1 RedisList 为什么选 Deque

`RedisList` 内部使用 `ArrayDeque<BytesWrapper>`：

```java
private final Deque<BytesWrapper> values = new ArrayDeque<>();
```

我当前只实现了列表头部插入与弹出，`Deque` 可以用 `addFirst` 和 `pollFirst` 直接表达语义。

`LPUSH key a b c` 会依次将 `a`、`b`、`c` 放到头部，最终列表顺序是：

```text
c -> b -> a
```

`LRANGE` 还要处理 Redis 的负数下标和闭区间语义。例如 `-1` 表示最后一个元素，`LRANGE key 0 -1` 表示读取全部列表。

我在值对象内完成下标归一化，而不是让命令层处理容器细节。这样 `LRange` 命令只需要解析参数并调用 `redisCore.listRange()`。

### 3.2 RedisHash 为什么选 HashMap

`RedisHash` 内部保存：

```java
private final Map<BytesWrapper, BytesWrapper> values = new HashMap<>();
```

Hash 里的 field 和 value 仍然使用 `BytesWrapper`，而不是 Java `String`。原因和 RESP BulkString 一样：Redis 的数据内容应保持二进制安全，不应在核心存储层过早做 UTF-8 解码。

`HSET` 的返回值不是本次处理的 field 数量，而是真正新增的 field 数量。覆盖旧值不计入返回值：

```java
if (!values.containsKey(entry.getKey())) {
    addedCount++;
}
values.put(entry.getKey(), entry.getValue());
```

我还在修改前先校验所有 field 和 value。这样即使批量参数中存在非法值，也不会出现“前一半已经写入，后一半才报错”的部分修改。

### 3.3 RedisSet 为什么选 HashSet

`RedisSet` 内部使用：

```java
private final Set<BytesWrapper> values = new HashSet<>();
```

`HashSet` 天然保证成员唯一，并且添加、删除和判断存在的平均时间复杂度都是 O(1)。

`SADD key a a b` 虽然接收了三个参数，但返回值只统计实际新增的唯一成员。`HashSet.add()` 的 boolean 返回值刚好可以直接表达这个语义。

---

## 四、为什么值对象内部还要 synchronized

顶层数据字典是：

```java
ConcurrentHashMap<BytesWrapper, RedisData>
```

`ConcurrentHashMap` 可以保证 key-value 映射的并发安全，但它不会自动保证 `RedisList` 内部的 `ArrayDeque` 或 `RedisHash` 内部的 `HashMap` 安全。

例如两个连接同时对一个 List 执行 `LPUSH`，它们从顶层 Map 取到的可能是同一个 `RedisList` 对象。如果容器内部没有同步，`ArrayDeque` 仍然会发生竞态。

因此我当前使用两层保护：

1. `ConcurrentHashMap.compute/computeIfPresent` 保证同 key 的创建、替换和删除是原子的；
2. `RedisList/RedisHash/RedisSet` 的公开操作使用 `synchronized`，保护内部非线程安全容器。

这不是性能最强的方案，但在当前阶段非常清晰：锁粒度落在单个 key 和单个值对象上，而不是为整个数据库加一把大锁。

---

## 五、用 compute 统一原子创建、类型检查和过期处理

写集合时，我没有使用“先 get，不存在再 put”的方式。

下面这种写法存在竞态窗口：

```java
RedisData value = map.get(key);
if (value == null) {
    map.put(key, new RedisList());
}
```

两个线程可能同时判断 key 不存在，然后分别创建对象，后写入的对象会覆盖先写入的对象。

我把“检查过期、创建容器、检查类型、修改数据”放进一次 `map.compute()`：

```java
map.compute(key, (currentKey, currentValue) -> {
    if (isExpired(currentValue)) {
        currentValue = null;
    }

    RedisList redisList;
    if (currentValue == null) {
        redisList = new RedisList();
    } else if (currentValue instanceof RedisList list) {
        redisList = list;
    } else {
        throw new WrongTypeException();
    }

    resultLength.set(redisList.leftPush(elements));
    return redisList;
});
```

这里有三个重要语义。

### 5.1 已过期 key 等同于不存在

如果原 key 已经过期，新的 `LPUSH/HSET/SADD` 可以直接创建新类型，而不应该因为过期对象的旧类型返回 WRONGTYPE。

### 5.2 类型不匹配返回标准 WRONGTYPE

如果 key 未过期，但它的实际类型与命令不匹配，我抛出统一的领域异常：

```java
public class WrongTypeException extends RuntimeException {
    public WrongTypeException() {
        super("WRONGTYPE Operation against a key holding the wrong kind of value");
    }
}
```

`CommandHandler` 会把它编码为 RESP Error，但不会再错误地补一个 `ERR ` 前缀。

### 5.3 空集合不保留 key

Redis 不会保留空 List、空 Hash 或空 Set。因此删掉最后一个内部元素时，`computeIfPresent` 必须返回 null，让顶层 Map 同时删除 key：

```java
deletedCount.set(redisHash.delete(fields));
if (redisHash.size() == 0L) {
    return null;
}
return redisHash;
```

这个细节会直接影响 `TYPE`、`EXISTS`、`DEL` 和 TTL 语义，不能只在命令响应上返回 0 就结束。

---

## 六、我为什么让命令层只负责参数与 RESP

每个命令仍然遵循第三阶段建立的边界：

```text
setContent(Resp[])
    → 验证参数数量和 RESP 类型
    → 转换为 BytesWrapper/long 等领域参数

handle(RedisCore)
    → 调用核心存储
    → 把结果包装为 Resp
```

以 `SADD` 为例，命令层会校验：

- 至少包含 key 和一个 member；
- key 和 member 都必须是 BulkString；
- key 和 member 不能是 NIL；
- 只有所有参数通过后，才修改命令对象字段。

实际执行保持很薄：

```java
@Override
public Resp handle(RedisCore redisCore) {
    return new RespInt(redisCore.addSetMembers(key, members));
}
```

这样分层后，我可以在不启动 Netty 服务器的情况下，分别测试值对象、RedisCore 和 Command。

---

## 七、写命令为什么能自动进入 AOF

这一阶段新增了很多写命令，例如 `LPUSH`、`LPOP`、`HSET`、`HDEL`、`SADD`、`SREM` 和 `DEL`。

我没有在 `CommandHandler` 里继续增加命令名判断，而是让修改数据的命令实现 `WriteCommand`：

```java
public class HSet implements WriteCommand {
    // ...
}
```

`CommandHandler` 只需要判断命令是否实现这个接口，就能在命令执行成功后把原 RESP 命令追加到 AOF。

相对 TTL 命令需要覆盖 `toAofCommands()`，转成绝对时间命令；普通集合写命令使用 `WriteCommand` 的默认实现即可。

这让持久化层不需要知道 List、Hash 或 Set 的内部存储结构。AOF 仍然只记录命令，重启时重放同一条命令链路。

---

## 八、我如何实现 SCAN、HSCAN 和 SSCAN

Redis GUI 客户端通常不会直接使用 `KEYS *`，而是通过 `SCAN` 分页加载 key。进入 Hash 或 Set 详情页后，它们又会继续发送 `HSCAN` 或 `SSCAN`。

为了支持这些客户端，我实现了统一的选项语义：

```text
SCAN cursor [MATCH pattern] [COUNT count]
HSCAN key cursor [MATCH pattern] [COUNT count]
SSCAN key cursor [MATCH pattern] [COUNT count]
```

### 8.1 响应格式

三个命令都返回两个元素的 RESP Array：

```text
[
    下一个游标,
    本页元素数组
]
```

游标返回 `0` 表示本轮扫描结束。`HSCAN` 的第二个数组按 `field, value, field, value` 展平，`MATCH` 只匹配 field，不匹配 value。

### 8.2 用模板方法复用 HSCAN 和 SSCAN

`HSCAN` 和 `SSCAN` 的参数解析、MATCH、COUNT、分页和响应结构几乎一样，只有三个地方不同：

1. 怎么获取待扫描元素；
2. 用什么文本进行 MATCH；
3. 怎么把一个元素编码成 RESP。

因此我提取了 `AbstractCollectionScan<T>`，把公共流程放在抽象类中，子类只填充变化点。

`SScan` 最终只需要实现：

```java
protected List<BytesWrapper> getItems(RedisCore redisCore, BytesWrapper key) {
    return redisCore.scanSetMembers(key);
}

protected String getMatchText(BytesWrapper item) {
    return item.toUtf8String();
}

protected Resp[] encodeItem(BytesWrapper item) {
    return new Resp[]{new BulkString(item)};
}
```

这是我在本项目中对模板方法的一次实际应用：不是为了“使用设计模式”而抽象，而是因为重复流程和可变点已经非常清晰。

### 8.3 当前 SCAN 是教学版实现

我当前的实现会：

1. 拷贝当前数据快照；
2. 按 `BytesWrapper` 字节顺序排序；
3. 用数组下标作为游标；
4. 根据 COUNT 返回一页数据。

这个方案的优点是简单、可预测、容易测试，足以支持当前 GUI 客户端。

但它和官方 Redis 使用的渐进式哈希桶游标不同。每次扫描都要全量拷贝和排序，时间复杂度可以达到 O(N log N)，不适合百万 key 场景。

我选择把这个限制明确写进 README 和性能报告，而不是把“能返回结果”包装成“完全等价官方 Redis”。

---

## 九、为了兼容 GUI 客户端，我补了 SELECT、TYPE 和 DEL

在 `redis-cli` 中，手工执行 `SET/GET` 一直没有问题。但当我用 Redis GUI 连接时，客户端会自动发送一些探测命令：

```text
SELECT 0
SCAN 0 ...
TYPE key
```

如果服务端不支持它们，即使 `SET` 和 `GET` 已经正确，GUI 仍然会表现为“无法连接”或“无法加载键”。

我因此补充了：

- `SELECT 0`：兼容默认数据库，其他库仍明确拒绝；
- `TYPE key`：返回 `string/list/hash/set/none`；
- `DEL key [key ...]`：返回实际删除数量，过期 key 视为不存在；
- `SCAN`：为 GUI 键列表提供分页数据。

这让我意识到：“协议可以连接”不等于“客户端生态可以使用”。工具兼容性往往依赖一组看似不起眼的探测命令。

---

## 十、命令工厂继续使用 Supplier 创建新对象

第七阶段结束时，`CommandFactory` 已经注册 25 个命令。

注册表保存的仍然不是命令实例，而是创建器：

```java
COMMAND_MAP.put("LPUSH", LPush::new);
COMMAND_MAP.put("HSET", HSet::new);
COMMAND_MAP.put("SADD", SAdd::new);
COMMAND_MAP.put("SCAN", Scan::new);
```

命令对象会在 `setContent()` 中保存本次请求的 key、field 或 member，因此它是有状态对象。如果多个连接复用同一个实例，参数会被并发覆盖。

`Supplier<Command>` 保证每个请求都会 `new` 一个命令对象，新增命令也只需要扩展枚举和注册表，不需要继续堆叠大量 `if-else`。

---

## 十一、我还增加了命令级 traceId 日志

命令数量增加后，只靠断点调试已经不方便观察服务端行为。所以我在本阶段引入 SLF4J + Log4j2，并为每个命令请求生成 `traceId`。

日志会记录：

- 连接标识与客户端地址；
- 命令名称；
- 响应 RESP 类型；
- 命令执行耗时；
- 参数错误、WRONGTYPE、AOF 失败与未预期异常。

为了避免敏感数据泄漏，正常请求日志不记录 key/value 明文。默认日志会同时输出到控制台和滚动文件，并可通过 JVM 参数调整级别与目录。

这部分让项目从“只能跑”向“能够观测和定位问题”前进了一步。

---

## 十二、我如何测试这一阶段

本阶段结束时，项目共有 **40 个测试类、158 个测试用例**，全部通过。

```bash
mvn test
```

```text
Tests run: 158, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

我没有只测试“命令能返回结果”，而是按层验证。

### 12.1 值对象测试

- List 的头部压入、弹出、长度和负数范围；
- Hash 的新增计数、覆盖、删除和快照；
- Set 的去重、删除、成员判断和快照。

### 12.2 RedisCore 测试

- key 不存在时自动创建正确数据类型；
- key 已过期时按不存在处理；
- key 类型不匹配时抛出 `WrongTypeException`；
- 删掉最后一个元素后同时删除 key；
- `DEL` 对不存在、过期和正常 key 的计数。

### 12.3 命令测试

- 正常 RESP 输入与返回值；
- 参数数量错误；
- 参数不是 BulkString；
- NIL key、field、value 或 member；
- `SCAN/HSCAN/SSCAN` 的游标、MATCH、COUNT 和非法选项。

### 12.4 Netty 集成测试

通过 `EmbeddedChannel` 把 RESP 请求真正送进 `CommandHandler`，验证：

- 新命令可以经过命令工厂正常分发；
- WRONGTYPE 会转换成标准 RESP Error；
- 写命令会进入 AOF；
- 业务错误不会导致连接意外关闭。

---

## 十三、性能测试让我看到的问题

完成数据结构后，我用 `redis-benchmark` 对项目进行了一次基准测试。

本机回环环境下，默认开启 AOF 时，`SET` 只有约 259 ops/s；关闭 AOF 后可以达到约 43,290 ops/s。

问题不在 Netty 无法处理并发，而是当前每条写命令都会在全局锁中单独执行一次 `channel.force(false)`。50 个并发写请求被排成了一条队列，吞吐上限几乎等于硬盘每秒能执行的 fsync 数量。

这一次测试让我对“正确性优先”有了更完整的理解：

- 先做同步 AOF，可以得到一条简单、可验证的正确性基线；
- 基线正确不代表实现已经完成，还需要用数据定位瓶颈；
- 性能优化必须明确会改变哪些持久性语义，不能只追求 ops/s。

完整测试方法、官方 Redis 对照和优化路线记录在 [MiniRedis 性能测试报告](../docs/benchmark-report-2026-08-20.md)中。

---

## 十四、这一阶段的取舍与不足

第七阶段选择了教学项目中相对清晰的实现，但我也明确保留了一些边界。

### 14.1 实现的是官方语义子集

当前并没有实现 `RPUSH/RPOP`、`HMGET/HGETALL`、Set 交并差集、ZSet 等更多命令。项目目标仍然是把一条代表性主链路做清楚，而不是追求命令数量。

### 14.2 集合内部的 synchronized 会串行化同 key 访问

当前方案优先保证实现可理解与并发正确性。同一个大 Hash 或 Set 的高并发读会受到对象锁限制，后续可以通过更细粒度数据结构或不可变快照降低读竞争。

### 14.3 SCAN 的 COUNT 是当前实现的页大小

官方 Redis 把 COUNT 定义为工作量提示，返回数量不保证严格等于 COUNT。当前项目为了实现简单可测，把它用作分页数量上限。

### 14.4 当前性能还不是生产级

全局 AOF 写锁、每条命令同步刷盘、全量扫描、AOF 整文件读取和周期过期全表遍历，都是已知的性能优化点。

把这些边界讲清楚，比把项目包装成“已经等价 Redis”更有价值。

---

## 十五、如果面试官问我，我会怎么讲

我会把第七阶段总结成下面几个技术点：

1. **多类型存储模型**：顶层用 `RedisData` 统一 String、List、Hash、Set，TTL 能力落在所有值对象上。
2. **同 key 原子更新**：通过 `ConcurrentHashMap.compute/computeIfPresent` 把过期检查、类型判断、容器创建和更新放在一个原子路径。
3. **容器级并发安全**：顶层 Map 安全不代表内部集合安全，当前用值对象锁保护 `ArrayDeque/HashMap/HashSet`。
4. **命令层分工**：命令负责 RESP 参数校验和响应组装，RedisCore 负责数据语义与并发。
5. **AOF 扩展性**：写命令实现 `WriteCommand` 后自动复用持久化链路，持久化层不需要知道内存容器结构。
6. **SCAN 取舍**：当前快照 + 排序 + 下标游标便于验证和 GUI 兼容，但不适合大数据，后续要改为渐进扫描。
7. **用性能数据反推设计问题**：基准测试定位到同步 AOF 串行 fsync 瓶颈，后续优化必须同时说明吞吐与数据安全的交换。

这些内容比单纯说“我实现了 25 个命令”更能展示项目的技术深度。

---

## 十六、下一阶段

第七阶段之后，我不会立即堆叠更多命令，而是进入 **Stage 8：AOF 性能优化**。

下一阶段会围绕：

- 拆分 AOF append 与 flush；
- 增加 `always/everysec` 刷盘策略；
- 把高成本 fsync 从 Netty EventLoop 中移出；
- 评估后台刷盘与组提交；
- 重跑基准测试，用数据验证优化结果；
- 明确 everysec 最多丢失约 1 秒数据的持久性取舍。

ZSet 和更多数据结构会放在后续阶段。当前更重要的是，把已经实现的存储主链路从“正确可用”继续推向“可以解释性能与可靠性取舍”。

---

## 结语

第七阶段让我的 Mini Redis 第一次真正具备了多数据结构存储能力。

我从 List、Hash 和 Set 的容器选型出发，继续把 TTL、并发原子性、WRONGTYPE、空集合生命周期、RESP 命令、AOF 和 Netty 连接在同一条链路上。同时，`SCAN/HSCAN/SSCAN` 和 GUI 客户端兼容问题，也让我看到了协议、服务端和客户端生态之间的关系。

下一篇，我会继续记录 AOF 性能优化过程：为什么当前的同步刷盘只有数百 ops/s，Redis 的 everysec 思路如何用吞吐换取小段数据安全窗口，以及我如何用压测数据验证改造是否真正有效。

如果你也在学习 Redis、Netty、Java 并发或准备后端面试，欢迎继续关注这个从 0 手敲 Mini Redis 的系列。后续我仍会按照“设计思路 + 完整代码 + 测试验证 + 问题复盘”的方式，继续把这个项目向前推进。
