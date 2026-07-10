# 从 0 手敲一个 Mini Redis：第一阶段，我先把内存核心跑通

## 摘要

这一阶段是整个 Mini Redis 项目的起点。我没有一开始就写网络层，也没有直接接 RESP 协议，而是先把最底层的内存核心搭起来。

第一阶段完成了 `BytesWrapper`、`RedisData`、`RedisString`、`RedisCore` 和 `RedisCoreImpl`。它们共同解决一个最基础的问题：

```text
Redis 的 key/value 在 Java 里应该怎么表示、怎么存、怎么取
```

这篇文章记录第一阶段的设计思路：为什么 key 不直接用 `String`，为什么 value 要抽象成 `RedisData`，为什么 `RedisCoreImpl.get()` 不只是简单 `map.get()`，以及这个阶段如何为后续 RESP、命令层和 TTL 打基础。

---

## 一、为什么第一阶段不先写服务端

刚开始做 Mini Redis 时，很容易想先把服务端跑起来，然后用 `redis-cli` 连一下。

但我现在的目标不是先做一个看起来能连的外壳，而是按层把 Redis 的核心能力做出来。

一个简化版 Redis 至少可以拆成几层：

```text
网络层 -> RESP协议层 -> 命令层 -> RedisCore存储层
```

如果最底层的存储核心还没准备好，前面的网络层和协议层即使写出来，也没有真正可操作的数据系统。

所以第一阶段我只做一件事：

```text
先把内存数据库核心跑通
```

也就是先解决：

```text
key -> value
```

---

## 二、第一阶段完成了哪些类

这一阶段主要完成了五个核心类：

- `BytesWrapper`
- `RedisData`
- `RedisString`
- `RedisCore`
- `RedisCoreImpl`

它们的关系可以概括成：

```text
BytesWrapper 负责表示 key 和二进制值
RedisData 负责抽象 Redis 中的 value
RedisString 是第一种具体 value 类型
RedisCore 定义存储核心能力
RedisCoreImpl 用 ConcurrentHashMap 实现核心存储
```

这一阶段完成后，项目已经可以在内存里完成最基础的存取操作。

---

## 三、`BytesWrapper`：为什么不直接用 String

第一阶段我先实现了 `BytesWrapper`。

它的职责是包装 `byte[]`，让字节数组可以作为 key 或 value 使用。

一开始可能会觉得，Redis 的 key/value 用 `String` 不就行了。实际不应该这么早退化成字符串，原因有两个。

第一，Redis 协议和 Redis 存储语义本质上更偏字节。  
客户端传来的 key/value 最开始就是字节流，后续 RESP 的 Bulk String 也是带长度的字节块。

第二，Java 的 `byte[]` 不能直接安全地作为 `Map` 的 key。  
原生数组的 `equals()` 和 `hashCode()` 默认不是按内容比较，而是按对象引用比较。

所以我需要一个值对象，把字节数组包装起来，并实现：

- `equals()`
- `hashCode()`
- `compareTo()`
- `toUtf8String()`

这样同样内容的字节数组才能被认为是同一个 key。

---

## 四、`BytesWrapper` 的比较逻辑

`BytesWrapper` 里最重要的是两个能力。

第一个是按内容比较：

```java
Arrays.equals(content, that.content)
```

第二个是按内容计算哈希：

```java
Arrays.hashCode(content)
```

这样它才能放进 `ConcurrentHashMap` 作为 key。

另外我还实现了 `compareTo()`，按字节顺序比较两个 `BytesWrapper`。这在第一阶段暂时不是核心能力，但它为后续可能的排序结构、zset 或扫描能力预留了空间。

这一点让我明确了一件事：

```text
底层值对象要先把相等性、哈希和比较规则定义清楚
```

否则后面的存储层会建立在不稳定的 key 语义上。

---

## 五、`RedisData`：为什么 value 要先抽象

第一阶段第二个关键类是 `RedisData`。

它是 Redis 数据对象的顶层接口，目前只定义了：

```java
long timeout();

void setTimeout(long timeout);
```

这里没有直接把 value 写成 `BytesWrapper`，原因是 Redis 未来不只有字符串。

后面还会有：

- list
- hash
- set
- zset

如果第一阶段直接把存储结构写成：

```java
Map<BytesWrapper, BytesWrapper>
```

那后面扩展其他数据结构时就会很别扭。

所以第一阶段先把 value 抽象成：

```java
RedisData
```

这样 `RedisCore` 只关心“这是一个 Redis 数据对象”，不关心它具体是字符串、列表还是哈希。

---

## 六、为什么 `RedisData` 目前只放 timeout

`RedisData` 现在只放了过期时间相关方法，这个设计比较克制。

原因是不同 Redis 数据类型之间，真正通用的能力其实不多。

`RedisString` 有 value。  
`RedisList` 未来可能有 deque。  
`RedisHash` 未来可能有 map。  
这些都不是所有类型共同拥有的字段。

但过期时间是 Redis key 级别的通用能力。

所以第一阶段先把 timeout 放进 `RedisData`，表示所有 Redis 数据对象都可以有过期时间。

这里的一个设计原则是：

```text
接口只放真正通用的能力，不提前塞具体类型的细节
```

---

## 七、`RedisString`：第一种具体数据类型

有了 `RedisData` 之后，我实现了第一种具体数据类型：

```java
RedisString
```

它内部主要有两部分：

```java
private volatile long timeout = -1;
private BytesWrapper value;
```

`value` 保存实际数据。  
`timeout` 保存过期时间。

当前约定：

```text
timeout = -1 表示永不过期
```

如果后续设置过期时间，会保存成绝对时间戳，例如：

```java
System.currentTimeMillis() + seconds * 1000
```

这样判断是否过期时，只需要和当前时间比较。

---

## 八、`RedisCore`：把存储能力抽象出来

`RedisCore` 是第一阶段的核心接口。

它目前定义了三个方法：

```java
void put(BytesWrapper key, RedisData value);

RedisData get(BytesWrapper key);

boolean exist(BytesWrapper key);
```

这个接口的作用是把命令层和底层存储隔开。

未来 `SET` 命令只需要调用：

```java
redisCore.put(key, value);
```

`GET` 命令只需要调用：

```java
redisCore.get(key);
```

命令层不应该知道底层到底是 `ConcurrentHashMap`，还是以后换成其他结构。

所以 `RedisCore` 的职责是定义 Redis 存储核心对外暴露的能力。

---

## 九、`RedisCoreImpl`：用 ConcurrentHashMap 实现内存核心

`RedisCoreImpl` 是第一阶段真正的存储实现。

内部结构是：

```java
private final ConcurrentHashMap<BytesWrapper, RedisData> map = new ConcurrentHashMap<>();
```

当前阶段使用 `ConcurrentHashMap` 有两个原因。

第一，结构简单，能快速完成内存 key/value 存储。  
第二，后面会有 TTL、服务端和可能的并发访问，使用并发 Map 更稳。

`put()` 很直接：

```java
map.put(key, value);
```

但 `get()` 不是简单的 `map.get()`。

---

## 十、`get()` 为什么要处理惰性删除

`RedisCoreImpl.get()` 当前逻辑是：

1. 从 map 中取出数据
2. 如果不存在，返回 `null`
3. 如果 `timeout == -1`，说明不过期，直接返回
4. 如果已经过期，从 map 中删除，再返回 `null`

这就是惰性删除。

它的意义是：

```text
只有当 key 被访问时，才顺便判断它是否过期
```

这样第一阶段不需要额外后台线程，也能先具备基础过期语义。

这个设计会影响 `exist()`。

如果 `exist()` 直接使用：

```java
map.containsKey(key)
```

那么已经过期但还没被清理的 key 仍然会被认为存在。

所以当前实现是：

```java
return get(key) != null;
```

这样 `exist()` 也会走同一套过期判断逻辑。

---

## 十一、第一阶段的测试

第一阶段写了核心测试，主要验证：

- `BytesWrapper` 内容相同时可以正确比较
- `RedisCoreImpl` 可以写入数据
- `RedisCoreImpl` 可以读取数据
- `exist()` 可以判断 key 是否存在
- 过期数据可以通过 `get()` 惰性删除

这些测试不复杂，但验证了第一阶段最重要的事实：

```text
内存核心已经可以独立工作
```

---

## 十二、第一阶段完成后的结构

第一阶段结束后，项目具备了最底层的存储能力：

```text
BytesWrapper
  -> 表示 key/value 的字节语义

RedisData
  -> 抽象所有 Redis 数据对象

RedisString
  -> 第一种具体 Redis 数据类型

RedisCore
  -> 定义核心存储能力

RedisCoreImpl
  -> 用 ConcurrentHashMap 实现内存存储
```

这一阶段不涉及协议，不涉及命令，也不涉及网络。

它只负责把数据核心先做稳。

---

## 十三、这一阶段的收获

第一阶段最大的收获是理解了 Mini Redis 的底层地基。

Redis 服务表面上是一个网络服务，但最里面仍然是一个数据系统。  
如果没有稳定的存储核心，后面的协议解析、命令分发、Netty 服务端都只是外壳。

这一阶段也让我明确了几个基础设计点：

- key/value 应优先保留字节语义
- `byte[]` 需要包装后才能安全作为 Map key
- Redis value 应该抽象成 `RedisData`
- timeout 是所有数据类型的公共语义
- `get()` 可以承担惰性过期判断

这些设计会在后续阶段不断被复用。

---

## 十四、下一步

第一阶段完成后，下一阶段进入：

```text
stage/02-resp-protocol
```

第二阶段的目标是实现 RESP 协议层，把网络字节流和 Java 协议对象互相转换：

```text
ByteBuf <-> Resp对象
```

有了第一阶段的存储核心，第二阶段就可以专注处理协议对象，而不需要关心数据怎么存。

---

## 结尾引流

这是我从 0 手敲 Mini Redis 系列的第一阶段总结。

这一阶段完成的是内存核心。下一篇会进入 RESP 协议层，重点是把 Redis 协议中的 `SimpleString`、`BulkString`、`RespArray` 等对象建出来，并实现协议的编解码闭环。
