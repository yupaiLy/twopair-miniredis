# 从 0 手敲一个 Mini Redis：第二阶段，我实现了 RESP 协议的编解码闭环

## 摘要

第一阶段完成内存核心后，我的 Mini Redis 已经具备了最基础的 `key -> value` 存储能力。第二阶段的目标是继续补上 Redis 通信协议层，也就是 RESP 协议。

这一阶段我实现了 RESP 的对象模型、基础读取工具、`decode` 解码逻辑和 `encode` 编码逻辑。到这里，项目已经能够完成：

```text
ByteBuf -> Resp对象 -> ByteBuf
```

这篇文章记录第二阶段的实现过程：为什么要先建 RESP 对象模型，`BulkString` 为什么要保留字节语义，`NIL` 和空值有什么区别，以及 `decode/encode` 如何形成协议闭环。

---

## 一、第二阶段要解决什么问题

第一阶段做完后，我已经有了 `RedisCore` 和 `RedisCoreImpl`，可以在内存里存取数据。

但 Redis 服务不是直接接收 Java 对象的。客户端通过 TCP 发来的内容，本质上是一段按 RESP 协议编码的字节流。

比如在 `redis-cli` 中输入：

```bash
SET name twopair
```

网络上传输的内容会更接近：

```text
*3\r\n$3\r\nSET\r\n$4\r\nname\r\n$7\r\ntwopair\r\n
```

所以第二阶段的核心目标是：

```text
字节流 -> RESP对象
RESP对象 -> 字节流
```

也就是把协议层独立出来，为后面的命令层和服务端层提供稳定输入输出。

---

## 二、先建立 RESP 对象模型

我没有一开始就直接写 `decode()`。原因很简单：如果没有先定义协议对象，解析逻辑很容易变成一堆分散的 if/else 和字节操作。

这一阶段先定义了这些 RESP 类型：

- `SimpleString`
- `Errors`
- `RespInt`
- `BulkString`
- `RespArray`
- `RespType`
- `Resp`

这一步的重点不是代码量，而是建立协议对象系统。

RESP 不是一堆普通字符串，它有明确的类型语义：

```text
+OK\r\n        -> SimpleString
-ERR xxx\r\n   -> Errors
:100\r\n       -> RespInt
$4\r\nname\r\n -> BulkString
*3\r\n...      -> RespArray
```

先把对象模型建清楚，后面 `decode` 和 `encode` 才有明确目标。

---

## 三、`RespType`：集中管理协议标记

RESP 靠第一个字符区分类型：

- `+`：简单字符串
- `-`：错误或负号
- `:`：整数
- `$`：Bulk String
- `*`：数组
- `\r\n`：行结束

所以我定义了 `RespType`，把这些协议标记集中管理。

这样后面代码里判断协议类型时，不需要到处写魔法字符，而是使用明确语义：

```java
RespType.BULK_STRING.getCode()
RespType.ARRAY.getCode()
RespType.R.getCode()
RespType.N.getCode()
```

这让协议层代码更容易读，也更容易维护。

---

## 四、`SimpleString` 和 `Errors`：协议状态文本

`SimpleString` 对应：

```text
+OK\r\n
```

`Errors` 对应：

```text
-ERR unknown command\r\n
```

这两类数据更像协议状态文本，通常用于返回 `OK`、`PONG`、错误信息等短文本内容。

所以这里用 `String` 保存内容是合理的。它们不是主要的数据载体，也不会直接作为 Redis 的 key/value 存储。

---

## 五、`BulkString`：真正的数据载体

`BulkString` 是这一阶段最重要的类型之一。

它看起来也叫 String，但它和 `SimpleString` 不一样。`BulkString` 的协议格式是：

```text
$4\r\nname\r\n
```

这里的 `4` 表示后面内容的字节长度。

所以 `BulkString` 本质上不是 Java 里的普通字符串，而是：

```text
带长度的字节块
```

因此我让它内部保存 `BytesWrapper`，而不是直接保存 `String`。

这样设计和第一阶段保持一致：Redis 里的 key/value 优先保留字节语义，不要过早解释成 Java 字符串。

---

## 六、`BulkString.NIL` 和空字符串不是一回事

这一阶段我定义了：

```java
public static final BulkString NIL = new BulkString(null);
```

它对应 RESP 中的 Null Bulk String：

```text
$-1\r\n
```

这和空字符串不同。

空字符串是：

```text
$0\r\n\r\n
```

它表示值存在，只是内容长度为 0。

Null Bulk String 是：

```text
$-1\r\n
```

它表示值不存在。

所以当前设计里有三种状态：

```text
普通 BulkString：new BulkString(new BytesWrapper(bytes))
空 BulkString：new BulkString(new BytesWrapper(new byte[0]))
NIL BulkString：BulkString.NIL
```

这个区分很关键，因为后面 `GET` 不存在的 key 时，应该返回 `BulkString.NIL`，而不是空字符串。

---

## 七、`RespArray`：Redis 命令的协议载体

Redis 命令在 RESP 里通常是数组。

比如：

```text
*3\r\n$3\r\nSET\r\n$4\r\nname\r\n$7\r\ntwopair\r\n
```

这个数组表示：

- `SET`
- `name`
- `twopair`

所以 `RespArray` 是后面命令层的直接输入。

我也补了：

```java
public static RespArray NIL = new RespArray(null);
```

它对应 RESP 的 Null Array：

```text
*-1\r\n
```

虽然当前命令主链路里不常用，但从 RESP 协议建模角度，这是一个合理的语义补充。

---

## 八、基础工具：`getString()` 和 `getNumber()`

在写完整 `decode()` 前，我先拆出了两个基础方法：

```java
getString(ByteBuf buffer)
getNumber(ByteBuf buffer)
```

`getString()` 用来读取以 `\r\n` 结尾的协议文本，比如：

```text
OK\r\n
ERR unknown command\r\n
```

`getNumber()` 用来读取 RESP 中的数字字段，比如：

```text
100\r\n
-1\r\n
```

这些数字字段会出现在多个位置：

- `:100\r\n` 的整数值
- `$4\r\n` 的 Bulk String 长度
- `*3\r\n` 的数组长度
- `$-1\r\n` 的 NIL 标记

先拆出这两个底层动作，再写 `decode()`，协议解析逻辑会清楚很多。

---

## 九、`decode()`：从 ByteBuf 解析 RESP 对象

`decode()` 的思路是：

1. 读取第一个字节
2. 根据 RESP 类型前缀分支
3. 解析后续内容
4. 返回对应 RESP 对象

核心映射是：

```text
+ -> SimpleString
- -> Errors
: -> RespInt
$ -> BulkString
* -> RespArray
```

其中 `BulkString` 和 `RespArray` 是两个重点。

`BulkString` 不能靠“读到 CRLF”为止解析正文，而必须：

1. 先读长度
2. 再按长度读取指定字节数
3. 最后检查正文后的 `\r\n`

`RespArray` 则需要递归解析内部元素：

```java
array[i] = decode(buffer);
```

这让 RESP 数组可以自然包含多个 RESP 对象。

---

## 十、`encode()`：把 RESP 对象重新写回 ByteBuf

完成 `decode()` 后，我继续实现了反方向：

```java
encode(Resp resp, ByteBuf buffer)
```

它负责把 RESP 对象重新编码成协议字节流。

例如：

```java
new SimpleString("OK")
```

编码为：

```text
+OK\r\n
```

普通 Bulk String：

```java
new BulkString(new BytesWrapper("name".getBytes()))
```

编码为：

```text
$4\r\nname\r\n
```

`BulkString.NIL` 编码为：

```text
$-1\r\n
```

`RespArray` 则先写数组长度，再递归编码每个元素。

到这里，第二阶段就形成了完整闭环：

```text
ByteBuf -> Resp对象 -> ByteBuf
```

---

## 十一、关于编码和中文的边界

这一阶段我已经意识到一个问题：协议层不能随便把字节当字符。

当前代码里 `BulkString` 的正文保留为 `BytesWrapper`，这是正确方向，因为 Bulk String 是二进制安全的字节块。

但 `getString()` 对 `SimpleString` 和 `Errors` 的解析仍然偏简单，代码中也保留了 TODO：

```java
//todo 先建 RESP 类型体系，暂不支持中文
```

这里后续可以优化为更严格的 UTF-8 字节解码。

当前阶段先接受这个边界，因为本阶段重点是 RESP 主链路闭环。

---

## 十二、测试覆盖

第二阶段补了 `RespTest`，主要覆盖：

- RESP 对象创建
- `getString()`
- `getNumber()`
- `decode()`
- `encode()`

`decode()` 覆盖了：

- `SimpleString`
- `Errors`
- `RespInt`
- 普通 `BulkString`
- 空 `BulkString`
- `BulkString.NIL`
- `RespArray`
- 不完整 Bulk String 异常

`encode()` 覆盖了：

- `SimpleString`
- `Errors`
- `RespInt`
- 普通 `BulkString`
- 空 `BulkString`
- `BulkString.NIL`
- `RespArray`

这说明第二阶段的协议层已经不是只靠手工测试，而是有了基本自动化验证。

---

## 十三、这一阶段的收获

第二阶段最大的收获是：我开始真正从“字符串格式”转向“协议对象模型”思考。

RESP 不是简单文本拼接，而是一套有明确语义边界的协议对象：

```text
状态文本 -> SimpleString
错误文本 -> Errors
整数 -> RespInt
字节块 -> BulkString
数组 -> RespArray
```

同时我也明确了一个分层边界：

```text
协议层只负责 ByteBuf 和 Resp 对象之间的转换
命令语义不应该放在协议层
存储逻辑也不应该放在协议层
```

这为第三阶段的命令分发打好了基础。

---

## 十四、下一步

第二阶段完成后，下一阶段进入：

```text
stage/03-command-dispatch
```

目标是把：

```text
RespArray
```

转换成：

```text
Command
```

并实现最小命令闭环：

- `PING`
- `SET`
- `GET`

到那一步，项目会第一次把：

```text
RESP协议层 -> 命令层 -> RedisCore存储层
```

真正接起来。

---

## 结尾引流

这是我从 0 手敲 Mini Redis 系列的第二阶段总结。

第一阶段完成了内存核心，这一阶段完成了 RESP 协议层。下一篇会进入命令分发层，重点是把 RESP 数组解析成具体命令，并让 `SET/GET` 真正操作内存核心。
