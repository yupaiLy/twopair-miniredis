# 从 0 手敲一个 Mini Redis：第三阶段，我把 RESP 对象接到了命令执行层

## 摘要

完成第二阶段 RESP 协议层后，我的 Mini Redis 已经具备了 `ByteBuf <-> Resp对象` 的编解码能力。第三阶段的目标是继续往上走一层：把 RESP 数组转换成具体 Redis 命令，并让命令能够操作第一阶段完成的 `RedisCore`。

这一阶段我实现了 `Command` 抽象、`CommandType`、`CommandFactory`，并完成了 `PING`、`SET`、`GET` 三个最小命令。到这里，项目第一次打通了：

```text
RespArray -> Command -> RedisCore -> Resp响应
```

这篇文章记录第三阶段的设计过程、类职责、实现边界和测试验证。

---

## 一、第三阶段要解决什么问题

前两个阶段分别解决了两个基础问题。

第一阶段解决的是内存核心：

```text
key -> value
```

第二阶段解决的是协议对象：

```text
ByteBuf <-> Resp对象
```

但到第二阶段结束时，项目还不能真正执行 Redis 命令。原因很直接：协议层只能把字节解析成 `RespArray`，但还不知道这个数组到底代表 `PING`、`SET` 还是 `GET`。

所以第三阶段要解决的是：

```text
RespArray -> Command对象 -> 执行命令
```

也就是把协议层和核心存储层接起来。

---

## 二、为什么先做命令抽象

第三阶段我首先定义的是 `Command` 接口。

这个接口的意义是把所有 Redis 命令统一成一种执行模型。无论是 `PING`、`SET`、`GET`，上层都只需要知道：

```java
Resp response = command.handle(redisCore);
```

也就是说，调用方不需要关心具体命令内部怎么解析参数，也不需要直接操作 `RedisCore`。

当前 `Command` 接口包含三个方法：

```java
CommandType type();

void setContent(Resp[] array);

Resp handle(RedisCore redisCore);
```

这三个方法分别解决三个问题：

- `type()`：当前命令是什么类型
- `setContent(...)`：把 RESP 数组参数注入命令对象
- `handle(...)`：执行命令并返回 RESP 响应

这里我选择让 `handle()` 返回 `Resp`，而不是直接写网络响应。原因是当前第三阶段还没有进入 Netty 服务端，命令层只需要产出协议对象。真正写回客户端，是第四阶段服务端编码层的职责。

---

## 三、`CommandType`：先只保留最小闭环

这一阶段没有急着实现很多命令，只定义了：

```java
PING,
SET,
GET
```

这是有意控制范围。

`PING` 用来验证命令执行可以返回响应。  
`SET` 用来验证命令层可以写入 `RedisCore`。  
`GET` 用来验证命令层可以从 `RedisCore` 读取并返回 Bulk String。

这三个命令刚好构成第三阶段最小闭环。

---

## 四、`PING`：最小命令实现

`PING` 是第一个实现的命令。

它不需要解析复杂参数，也不需要访问存储层。执行结果就是：

```java
new SimpleString("PONG")
```

这一步的价值不是功能复杂度，而是验证 `Command` 抽象能跑通。

它打通的是：

```text
Command -> handle() -> Resp响应
```

这个链路看起来很短，但它是后面所有命令的统一执行模型。

---

## 五、`SET`：第一次写入 RedisCore

`SET` 是第三阶段真正开始连接第一阶段内存核心的地方。

对于命令：

```bash
SET name twopair
```

它经过 RESP 解码后，大概会得到这样的数组：

```text
array[0] = SET
array[1] = name
array[2] = twopair
```

所以 `Set.setContent(...)` 做的事情是从 `Resp[]` 中取出：

- key
- value

因为 Redis 命令参数通常是 Bulk String，所以这里从 `BulkString` 中取出 `BytesWrapper`：

```java
this.key = ((BulkString) array[1]).getBytesWrapper();
this.value = ((BulkString) array[2]).getBytesWrapper();
```

执行时，`Set.handle(...)` 会创建 `RedisString`，然后写入 `RedisCore`：

```java
RedisString redisString = new RedisString(value);
redisString.setTimeout(-1);
redisCore.put(key, redisString);
return new SimpleString("OK");
```

这一阶段暂时不处理 `EX`、`PX`、`NX`、`XX` 这些扩展参数。原因是当前目标是命令层最小闭环，TTL 会放在后续阶段单独处理。

---

## 六、`GET`：从 RedisCore 读出 RESP 响应

`GET` 和 `SET` 对称。

它从 RESP 参数里解析 key，然后通过：

```java
redisCore.get(key)
```

读取数据。

如果 key 不存在，返回：

```java
BulkString.NIL
```

如果 key 存在并且是 `RedisString`，返回：

```java
new BulkString(value)
```

这里有一个关键点：`GET` 返回的不是 Java 字符串，而是 RESP 协议对象。因为命令层的输出要交给第二阶段的 `Resp.encode(...)` 继续编码。

所以第三阶段形成的边界是：

```text
命令层输入：RespArray
命令层输出：Resp
```

这个边界很重要。它让命令层不依赖网络，也不直接处理字节流。

---

## 七、`CommandFactory`：把 RESP 数组变成命令对象

完成 `PING/SET/GET` 后，第三阶段还缺一个关键类：`CommandFactory`。

它负责把：

```text
RespArray
```

转换成：

```text
Command
```

核心流程是：

1. 取出 RESP 数组第一个元素
2. 确认它是 `BulkString`
3. 转成命令名
4. 根据命令名创建具体命令对象
5. 调用 `setContent(array)` 注入参数
6. 返回命令对象

这里使用了：

```java
Map<String, Supplier<Command>>
```

而不是直接保存命令实例。

原因是 `Command` 对象是有状态的，例如 `Set` 内部保存了当前请求的 `key` 和 `value`。如果多个请求复用同一个命令实例，参数会互相覆盖，也会有线程安全风险。

所以注册表里保存构造器：

```java
COMMAND_MAP.put("PING", Ping::new);
COMMAND_MAP.put("SET", Set::new);
COMMAND_MAP.put("GET", Get::new);
```

每次解析命令时创建新的命令对象。

---

## 八、第三阶段的主链路

到这一阶段结束，项目已经能完成这条链路：

```text
RespArray
  -> CommandFactory.from(...)
  -> Command
  -> command.handle(redisCore)
  -> Resp响应
```

以 `SET name twopair` 为例：

```text
RespArray
  -> CommandFactory 创建 Set
  -> Set.setContent 解析 key/value
  -> Set.handle 调用 redisCore.put
  -> 返回 SimpleString("OK")
```

以 `GET name` 为例：

```text
RespArray
  -> CommandFactory 创建 Get
  -> Get.setContent 解析 key
  -> Get.handle 调用 redisCore.get
  -> 返回 BulkString(value)
```

这说明第一阶段的内存核心和第二阶段的 RESP 协议对象，已经通过第三阶段命令层接起来了。

---

## 九、测试覆盖

这一阶段我补了几个测试：

- `PingTest`
- `SetTest`
- `GetTest`
- `CommandFactoryTest`

测试覆盖了：

- `PING` 返回 `PONG`
- `SET` 能写入 `RedisCore`
- `GET` 能读出已写入的值
- `GET` 不存在 key 时返回 `BulkString.NIL`
- `CommandFactory` 能识别 `PING/SET/GET`
- 不支持的命令会抛出异常
- `SET -> GET` 的命令工厂完整链路

当前全量测试结果：

```text
Tests run: 13, Failures: 0, Errors: 0
```

这说明第三阶段的命令分发主链路已经稳定。

---

## 十、这一阶段我学到的东西

第三阶段最大的收获不是实现了三个命令，而是理解了一个中间层的价值：

```text
协议层不应该直接操作存储层
存储层也不应该理解协议细节
命令层负责把两者连接起来
```

现在项目的层次变成：

```text
RESP协议层 -> Command命令层 -> RedisCore存储层
```

这个分层让后续继续接 Netty 服务端变得更清晰。第四阶段的服务端只需要负责：

1. 从 socket 读取 ByteBuf
2. 调用 `Resp.decode`
3. 调用 `CommandFactory.from`
4. 执行 `command.handle`
5. 调用 `Resp.encode`
6. 写回客户端

也就是说，第三阶段完成后，服务端层已经有了可以调用的核心能力。

---

## 十一、下一步

下一阶段会进入：

```text
stage/04-netty-server
```

目标是接入 Netty 服务端，真正打通：

```text
redis-cli -> TCP -> Resp.decode -> CommandFactory -> RedisCore -> Resp.encode -> redis-cli
```

到那一步，这个 Mini Redis 就会第一次具备在线交互能力。

---

## 结尾引流

这是我从 0 手敲 Mini Redis 系列的第三阶段总结。

前两阶段分别完成了内存核心和 RESP 协议层，这一阶段完成了命令抽象与分发。下一篇会继续进入服务端部分，重点是用 Netty 把前面三层真正串成一个可以被 `redis-cli` 连接的服务。
