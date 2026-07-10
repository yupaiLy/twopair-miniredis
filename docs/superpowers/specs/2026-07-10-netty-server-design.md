# 第四阶段设计：Netty 服务端接入

## 1. 目标与边界

第四阶段让已经完成的 RESP、命令分发和内存存储能力对外提供 TCP 服务。启动服务后，`redis-cli -p 6378` 可以执行 `PING`、`SET`、`GET`，多个客户端连接共享同一个内存数据库。

本阶段必须正确处理 TCP 拆包和粘包：

- 一个 RESP 命令被拆成多次网络读取时，不能提前解析或报错；
- 多个 RESP 命令在一次网络读取中到达时，必须逐条执行并逐条响应；
- 客户端发来的命令必须是 RESP Array，命令执行失败应返回 RESP Error，而不是让连接的业务线程直接异常退出。

本阶段不实现 TTL 命令、持久化、认证、多数据库、发布订阅和 Redis Cluster。

默认监听端口使用 `6378`，与前面使用的 `redis-cli -p 6378` 保持一致。

## 2. 架构与数据流

服务端采用职责单一的三段式 Netty Pipeline：

```text
客户端 ByteBuf
    -> RespDecoder
    -> CommandHandler
    -> RespEncoder
    -> 客户端 ByteBuf
```

### 2.1 `RespDecoder`

包名建议为 `cn.twopair.server.codec`，继承 Netty 的 `ByteToMessageDecoder`。

它的职责是把网络字节流转换为一个完整的 `Resp` 对象，不关心这是 PING、SET 还是 GET。选择 `ByteToMessageDecoder` 的原因是 Netty 会为它累计未消费的字节，并在输出了一个对象后继续调用解码器，因此天然适合处理拆包和粘包。

当前 `Resp.decode(ByteBuf)` 假设缓冲区中已经有完整协议数据，解析不完整数据时会消费一部分 readerIndex 后抛异常。为了让它安全用于网络层，本阶段新增一个面向流式输入的入口，例如：

```java
Resp resp = Resp.tryDecode(buffer);
if (resp != null) {
    out.add(resp);
}
```

`tryDecode` 的约定：

- 数据完整：返回 `Resp`，readerIndex 前进；
- 数据不完整：恢复到开始解析前的 readerIndex，并返回 `null`；
- 协议格式非法：抛出协议异常，不能误判成“等待更多字节”。

内部要区分“数据暂时不够”和“数据本身非法”。推荐新增仅供 RESP 包内部使用的 `RespIncompleteException`：所有读取不足的分支抛出它；`tryDecode` 捕获后重置 readerIndex 并返回 `null`；非法类型、非法数字和非法长度仍然抛出 `IllegalStateException` 或专门的协议异常。

这样不会把错误请求永久留在缓冲区中等待，也避免了靠异常消息文本判断错误类型。

### 2.2 `CommandHandler`

包名建议为 `cn.twopair.server.handler`，继承 `SimpleChannelInboundHandler<Resp>`。

它的职责是处理业务，而不是解析字节：

1. 校验入站对象是否为 `RespArray`；
2. 调用 `CommandFactory.from((RespArray) msg)` 创建本次请求专属的 `Command`；
3. 调用 `command.handle(redisCore)` 执行命令；
4. 通过 `ctx.writeAndFlush(response)` 将 RESP 响应交给出站编码器。

`RedisCore` 由 `RedisServer` 创建一次，并通过构造器注入每个 `CommandHandler`。不能在 Handler 或每个连接中 `new RedisCoreImpl()`：否则每个客户端都会拥有独立 Map，客户端 A 的 `SET` 对客户端 B 不可见。

`RedisCoreImpl` 当前使用 `ConcurrentHashMap`，满足多个 Netty EventLoop 共享访问的基本线程安全要求。

命令不支持、参数不合法、类型不匹配等业务错误，`CommandHandler` 转换为 `new Errors("ERR " + message)` 并保持连接可用。协议解析错误则由 `exceptionCaught` 返回错误并关闭连接，因为该连接的后续字节流已经不可信。

为避免 `RespArray.NIL` 和空命令导致空指针异常，需要在 `CommandFactory.from` 中补充 `respArray.getArray() == null` 的校验。这是服务端接入时必要的输入边界保护，不改变既有命令语义。

### 2.3 `RespEncoder`

包名建议为 `cn.twopair.server.codec`，继承 `MessageToByteEncoder<Resp>`。

它只负责把 `Resp` 写入 Netty 分配的 `ByteBuf`：

```java
Resp.encode(resp, out);
```

协议编码规则继续集中在已有的 `Resp.encode` 中。这样编码器是薄适配层，不会复制 RESP 编码逻辑；后面新增 RESP 类型时只维护协议层即可。

### 2.4 `RedisServer`

包名建议为 `cn.twopair.server`，负责生命周期和组装 Pipeline：

- 创建 boss 和 worker `NioEventLoopGroup`；
- 创建唯一的 `RedisCoreImpl`；
- 使用 `ServerBootstrap` 和 `NioServerSocketChannel` 绑定 `6378`；
- 为每条连接依次添加 `RespDecoder`、`CommandHandler`、`RespEncoder`；
- 在关闭时调用 `shutdownGracefully()` 释放线程资源。

`Main` 改为创建并启动 `RedisServer`，删除 IDE 生成的 Hello World 示例代码。

## 3. 方案取舍

### 方案一：一个 `ByteBuf` Handler 完成所有工作

Handler 内直接调用 `Resp.decode`、创建命令、执行、再调用 `Resp.encode`。实现文件少，但协议、网络和业务逻辑耦合，拆包处理难以正确实现，也不利于单独测试，故不采用。

### 方案二：三段式 Pipeline

解码、业务、编码各自独立，能够分别使用 `EmbeddedChannel` 测试；新增命令时无需修改网络层；RESP 解析异常和命令业务异常也能分开处理。本阶段采用此方案。

### 方案三：使用 `LineBasedFrameDecoder`

RESP 虽然使用 `\r\n`，但 Bulk String 的内容是二进制安全的，数据本身可能包含换行；数组还会嵌套多个元素。按行切分会破坏协议，不能采用。

## 4. 文件清单

| 文件 | 责任 | 依赖 |
| --- | --- | --- |
| `resp/Resp.java` | 增加安全的流式解析入口，区分不完整数据和非法协议 | `ByteBuf` |
| `resp/RespIncompleteException.java` | 标识“字节尚未收全”的内部解析状态 | 无 |
| `server/codec/RespDecoder.java` | 入站字节流 -> `Resp` | `Resp.tryDecode` |
| `server/handler/CommandHandler.java` | `RespArray` -> `Command` -> 响应 `Resp` | `CommandFactory`、`RedisCore` |
| `server/codec/RespEncoder.java` | 响应 `Resp` -> 出站字节流 | `Resp.encode` |
| `server/RedisServer.java` | Netty 启动、共享存储、Pipeline、资源释放 | 三个 Handler、`RedisCoreImpl` |
| `Main.java` | 应用入口 | `RedisServer` |
| `command/CommandFactory.java` | 补 NIL 数组和空数组的输入校验 | `RespArray` |

## 5. 测试与验收

### 5.1 单元测试

使用 JUnit 4 的 `Assert` 风格和 Netty `EmbeddedChannel`。

`RespDecoderTest`：

- 将 `*1\r\n$4\r\nPI` 和 `NG\r\n` 分两次写入；第一次 `Assert.assertNull(channel.readInbound())`，第二次读取到完整 PING 数组；
- 一次写入两个 PING 请求，连续两次 `readInbound()` 得到两个对象，证明可以处理粘包；
- 写入非法协议，断言抛出协议异常或关闭通道，不能把它当作未完成请求。

`CommandHandlerTest`：

- 写入 PING RESP Array，读取出站 `SimpleString("PONG")`；
- 使用同一个 `RedisCore` 执行 SET 再 GET，读取到对应 Bulk String；
- 写入未知命令，断言返回 `Errors`，并验证 Channel 仍可继续处理下一条合法命令。

`RespEncoderTest`：

- 写出 `SimpleString("PONG")`，断言出站字节为 `+PONG\r\n`。

### 5.2 手工联调

在 IDE 中运行 `Main` 后执行：

```bash
redis-cli -p 6378 ping
redis-cli -p 6378 set name twopair
redis-cli -p 6378 get name
```

预期分别得到：

```text
PONG
OK
twopair
```

随后开启第二个 `redis-cli -p 6378`，执行 `get name` 仍应返回 `twopair`，以验证连接间共享同一个 `RedisCore`。

## 6. 完成标准

- `mvn test` 全部通过；
- 服务可监听 `6378`；
- `redis-cli` 可稳定完成 PING、SET、GET；
- 拆包、粘包的 EmbeddedChannel 测试通过；
- 业务错误返回 RESP Error，协议错误不会被误认为拆包；
- 服务退出时 Netty EventLoop 资源被正常释放。
