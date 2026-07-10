# Netty Server Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 MiniRedis 通过 Netty 监听 TCP 端口 `6378`，并让 `redis-cli` 可以稳定执行 PING、SET、GET，同时正确处理 RESP 请求的拆包和粘包。

**Architecture:** 网络层按 Netty 的物理注册顺序使用 `RespDecoder -> RespEncoder -> CommandHandler`。入站字节先变为 `Resp`，业务 Handler 将命令交给已有的 `CommandFactory` 和共享 `RedisCore`；业务响应再由编码器写回字节流。RESP 层新增可回滚的流式解码入口，以区分数据未收全和协议格式错误。

**Tech Stack:** Java 17、Maven、Netty 4.2.5.Final、JUnit 4.13.2。

## Global Constraints

- 不新增 Maven 依赖，继续使用已有的 Netty 和 JUnit 4。
- 默认端口固定为 `6378`；自动化集成测试使用端口 `0`，避免占用本机端口。
- 所有测试使用 `org.junit.Assert` 断言；异常测试使用 `try-catch + Assert.fail(...)`。
- `Resp.tryDecode(ByteBuf)` 在数据不完整时必须返回 `null` 并恢复 readerIndex；格式非法时必须抛异常。
- Pipeline 注册顺序固定为 `RespDecoder`、`RespEncoder`、`CommandHandler`，不要交换编码器和业务 Handler 的位置。
- 每个 Netty 连接必须使用新的 `RespDecoder` 实例；所有连接必须共享同一个 `RedisCore` 实例。
- 每完成一个任务运行指定测试并提交；完成阶段后运行完整的 `mvn test` 和 `redis-cli` 手工联调。

---

## File Structure

| 文件 | 变更 | 责任 |
| --- | --- | --- |
| `src/main/java/cn/twopair/resp/RespIncompleteException.java` | 新建 | 标识 RESP 字节尚未收全的内部异常。 |
| `src/main/java/cn/twopair/resp/Resp.java` | 修改 | 提供可回滚的 `tryDecode`，并将所有“数据不够”分支统一标记为不完整。 |
| `src/test/java/cn/twopair/resp/RespTest.java` | 修改 | 验证流式解析会回滚 readerIndex，非法协议仍会失败。 |
| `src/main/java/cn/twopair/server/codec/RespDecoder.java` | 新建 | 入站 `ByteBuf -> Resp`。 |
| `src/test/java/cn/twopair/server/codec/RespDecoderTest.java` | 新建 | 用 `EmbeddedChannel` 验证拆包与粘包。 |
| `src/main/java/cn/twopair/server/codec/RespEncoder.java` | 新建 | 出站 `Resp -> ByteBuf`。 |
| `src/test/java/cn/twopair/server/codec/RespEncoderTest.java` | 新建 | 验证编码器输出标准 RESP 字节。 |
| `src/main/java/cn/twopair/server/handler/CommandHandler.java` | 新建 | `RespArray -> Command -> Resp`，并转换命令错误和协议错误。 |
| `src/main/java/cn/twopair/command/CommandFactory.java` | 修改 | 拒绝 NIL 数组、空数组和 NIL 命令名，避免空指针异常。 |
| `src/test/java/cn/twopair/command/CommandFactoryTest.java` | 修改 | 验证 NIL 命令数组会返回可预期的参数错误。 |
| `src/test/java/cn/twopair/server/handler/CommandHandlerTest.java` | 新建 | 验证 PING、SET、GET、错误响应和连接复用。 |
| `src/main/java/cn/twopair/server/RedisServer.java` | 新建 | 管理 Netty 生命周期、共享存储和每条连接的 Pipeline。 |
| `src/test/java/cn/twopair/server/RedisServerTest.java` | 新建 | 使用真实本地 Socket 验证服务启动及跨连接共享数据。 |
| `src/main/java/cn/twopair/Main.java` | 修改 | 启动并优雅关闭 `RedisServer`。 |

### Task 1: 让 RESP 解析支持流式输入

**Files:**

- Create: `src/main/java/cn/twopair/resp/RespIncompleteException.java`
- Modify: `src/main/java/cn/twopair/resp/Resp.java`
- Modify: `src/test/java/cn/twopair/resp/RespTest.java`

**Interfaces:**

- Produces: `static Resp Resp.tryDecode(ByteBuf buffer)`；完整 RESP 返回对象，不完整 RESP 返回 `null` 并恢复 `readerIndex`，非法 RESP 抛出 `IllegalStateException`。
- Produces: 包级 `RespIncompleteException extends IllegalStateException`，仅由 RESP 包内部用于控制流。
- Consumed by later tasks: `RespDecoder` 只调用 `Resp.tryDecode`，不能直接调用 `Resp.decode`。

- [ ] **Step 1: 先写失败测试，锁定回滚语义**

在 `RespTest` 末尾新增下面的方法。第一段数据缺少 `NG\r\n`，因此不能得到半个命令；第二段数据追加后，才应得到完整 PING 数组。

```java
@Test
public void testTryDecode() {
    ByteBuf incompleteBuffer = Unpooled.copiedBuffer("*1\r\n$4\r\nPI", StandardCharsets.UTF_8);
    int initialReaderIndex = incompleteBuffer.readerIndex();

    Resp incomplete = Resp.tryDecode(incompleteBuffer);
    Assert.assertNull(incomplete);
    Assert.assertEquals(initialReaderIndex, incompleteBuffer.readerIndex());

    incompleteBuffer.writeCharSequence("NG\r\n", StandardCharsets.UTF_8);
    Resp complete = Resp.tryDecode(incompleteBuffer);
    Assert.assertTrue(complete instanceof RespArray);
    RespArray pingArray = (RespArray) complete;
    Assert.assertEquals(1, pingArray.getArray().length);
    Assert.assertEquals("PING", ((BulkString) pingArray.getArray()[0]).getBytesWrapper().toUtf8String());

    ByteBuf invalidBuffer = Unpooled.copiedBuffer("?PING\r\n", StandardCharsets.UTF_8);
    try {
        Resp.tryDecode(invalidBuffer);
        Assert.fail("预期非法RESP类型应抛出 IllegalStateException");
    } catch (IllegalStateException e) {
        Assert.assertNotNull(e);
    }
}
```

- [ ] **Step 2: 运行单测，确认它因缺少 API 而失败**

Run:

```bash
mvn -Dtest=RespTest#testTryDecode test
```

Expected: 测试编译失败，错误包含 `cannot find symbol` 和 `tryDecode`。

- [ ] **Step 3: 添加不完整数据异常和最小解析实现**

新建 `RespIncompleteException.java`。它继承 `IllegalStateException`，因此现有 `testDecode` 对不完整 Bulk String 的断言无需修改。

```java
package cn.twopair.resp;

final class RespIncompleteException extends IllegalStateException {
    RespIncompleteException() {
        super("RESP数据不完整");
    }
}
```

在 `Resp.java` 的 `decode` 前添加以下入口。只捕获 `RespIncompleteException`；不要捕获全部 `IllegalStateException`，否则非法协议会被误判为等待更多数据。

```java
static Resp tryDecode(ByteBuf buffer) {
    buffer.markReaderIndex();
    try {
        return decode(buffer);
    } catch (RespIncompleteException e) {
        buffer.resetReaderIndex();
        return null;
    }
}
```

将 `decode`、`getString`、`getNumber` 替换为以下实现。`encode` 不修改。

```java
static Resp decode(ByteBuf buffer) {
    if (!buffer.isReadable()) {
        throw new RespIncompleteException();
    }

    byte type = buffer.readByte();
    if (type == RespType.STATUS.getCode()) {
        return new SimpleString(getString(buffer));
    }
    if (type == RespType.ERROR.getCode()) {
        return new Errors(getString(buffer));
    }
    if (type == RespType.INTEGER.getCode()) {
        return new RespInt(getNumber(buffer));
    }
    if (type == RespType.BULK_STRING.getCode()) {
        int length = getNumber(buffer);
        if (length == -1) {
            return BulkString.NIL;
        }
        if (length < 0) {
            throw new IllegalStateException("BulkString长度非法");
        }
        if (buffer.readableBytes() < 2 || length > buffer.readableBytes() - 2) {
            throw new RespIncompleteException();
        }

        byte[] bytes = new byte[length];
        buffer.readBytes(bytes);
        if (buffer.readByte() != RespType.R.getCode() || buffer.readByte() != RespType.N.getCode()) {
            throw new IllegalStateException("BulkString结尾必须是CRLF");
        }
        return new BulkString(new BytesWrapper(bytes));
    }
    if (type == RespType.ARRAY.getCode()) {
        int length = getNumber(buffer);
        if (length == -1) {
            return RespArray.NIL;
        }
        if (length < 0) {
            throw new IllegalStateException("Array长度非法");
        }

        Resp[] array = new Resp[length];
        for (int i = 0; i < length; i++) {
            array[i] = decode(buffer);
        }
        return new RespArray(array);
    }
    throw new IllegalStateException("未知RESP类型: " + (char) type);
}

static String getString(ByteBuf buffer) {
    StringBuilder builder = new StringBuilder();
    while (buffer.isReadable()) {
        byte value = buffer.readByte();
        if (value != RespType.R.getCode()) {
            builder.append((char) value);
            continue;
        }

        if (!buffer.isReadable()) {
            throw new RespIncompleteException();
        }
        if (buffer.readByte() != RespType.N.getCode()) {
            throw new IllegalStateException("字符串结尾必须是CRLF");
        }
        return builder.toString();
    }
    throw new RespIncompleteException();
}

static int getNumber(ByteBuf buffer) {
    if (!buffer.isReadable()) {
        throw new RespIncompleteException();
    }

    boolean positive = true;
    boolean hasDigit = false;
    int value = 0;
    byte first = buffer.readByte();
    if (first == RespType.NEGATIVE.getCode()) {
        positive = false;
    } else if (first >= RespType.ZERO.getCode() && first <= RespType.NINE.getCode()) {
        value = first - RespType.ZERO.getCode();
        hasDigit = true;
    } else {
        throw new IllegalStateException("数字格式非法");
    }

    while (buffer.isReadable()) {
        byte current = buffer.readByte();
        if (current == RespType.R.getCode()) {
            if (!buffer.isReadable()) {
                throw new RespIncompleteException();
            }
            if (buffer.readByte() != RespType.N.getCode()) {
                throw new IllegalStateException("数字结尾必须是CRLF");
            }
            if (!hasDigit) {
                throw new IllegalStateException("数字格式非法");
            }
            return positive ? value : -value;
        }
        if (current < RespType.ZERO.getCode() || current > RespType.NINE.getCode()) {
            throw new IllegalStateException("数字格式非法");
        }
        value = value * 10 + current - RespType.ZERO.getCode();
        hasDigit = true;
    }
    throw new RespIncompleteException();
}
```

- [ ] **Step 4: 运行 RESP 全量测试，确认旧行为不回归**

Run:

```bash
mvn -Dtest=RespTest test
```

Expected: `Tests run: 6, Failures: 0, Errors: 0`。

- [ ] **Step 5: 提交流式解析能力**

```bash
git add src/main/java/cn/twopair/resp/Resp.java src/main/java/cn/twopair/resp/RespIncompleteException.java src/test/java/cn/twopair/resp/RespTest.java
git commit -m "feat(resp): support streaming RESP decode"
```

### Task 2: 编写入站 RESP 解码器

**Files:**

- Create: `src/main/java/cn/twopair/server/codec/RespDecoder.java`
- Create: `src/test/java/cn/twopair/server/codec/RespDecoderTest.java`

**Interfaces:**

- Consumes: `Resp.tryDecode(ByteBuf)`。
- Produces: 每个完整入站 RESP 向后传播一个 `Resp`；数据不完整时不传播对象也不移动 readerIndex。
- Consumed by later tasks: `RedisServer` 将为每一条 `SocketChannel` 创建一个 `RespDecoder`。

- [ ] **Step 1: 写拆包和粘包的失败测试**

新建 `RespDecoderTest.java`：

```java
package cn.twopair.server.codec;

import cn.twopair.resp.BulkString;
import cn.twopair.resp.RespArray;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class RespDecoderTest {

    @Test
    public void testDecodeSplitAndCoalescedRequest() {
        EmbeddedChannel channel = new EmbeddedChannel(new RespDecoder());

        Assert.assertFalse(channel.writeInbound(Unpooled.copiedBuffer("*1\r\n$4\r\nPI", StandardCharsets.UTF_8)));
        Assert.assertNull(channel.readInbound());

        Assert.assertTrue(channel.writeInbound(Unpooled.copiedBuffer("NG\r\n", StandardCharsets.UTF_8)));
        RespArray splitPing = channel.readInbound();
        Assert.assertEquals("PING", ((BulkString) splitPing.getArray()[0]).getBytesWrapper().toUtf8String());

        String ping = "*1\r\n$4\r\nPING\r\n";
        Assert.assertTrue(channel.writeInbound(Unpooled.copiedBuffer(ping + ping, StandardCharsets.UTF_8)));
        RespArray firstPing = channel.readInbound();
        RespArray secondPing = channel.readInbound();
        Assert.assertEquals("PING", ((BulkString) firstPing.getArray()[0]).getBytesWrapper().toUtf8String());
        Assert.assertEquals("PING", ((BulkString) secondPing.getArray()[0]).getBytesWrapper().toUtf8String());
        Assert.assertNull(channel.readInbound());

        channel.finishAndReleaseAll();
    }
}
```

- [ ] **Step 2: 运行测试，确认缺少解码器而失败**

Run:

```bash
mvn -Dtest=RespDecoderTest test
```

Expected: 测试编译失败，错误包含 `cannot find symbol` 和 `RespDecoder`。

- [ ] **Step 3: 实现只做协议适配的 Decoder**

新建 `RespDecoder.java`。不要在这里调用 `CommandFactory` 或 `RedisCore`；它只解决字节流边界问题。

```java
package cn.twopair.server.codec;

import cn.twopair.resp.Resp;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

public class RespDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        Resp resp = Resp.tryDecode(in);
        if (resp != null) {
            out.add(resp);
        }
    }
}
```

`ByteToMessageDecoder` 在本次 `decode` 输出了对象后会继续尝试解析剩余字节，所以一次接收两个 PING 时，测试可以连续读取两个 `RespArray`。

- [ ] **Step 4: 运行解码器测试**

Run:

```bash
mvn -Dtest=RespDecoderTest test
```

Expected: `Tests run: 1, Failures: 0, Errors: 0`。

- [ ] **Step 5: 提交入站解码器**

```bash
git add src/main/java/cn/twopair/server/codec/RespDecoder.java src/test/java/cn/twopair/server/codec/RespDecoderTest.java
git commit -m "feat(server): add RESP decoder"
```

### Task 3: 编写出站 RESP 编码器

**Files:**

- Create: `src/main/java/cn/twopair/server/codec/RespEncoder.java`
- Create: `src/test/java/cn/twopair/server/codec/RespEncoderTest.java`

**Interfaces:**

- Consumes: 现有 `Resp.encode(Resp, ByteBuf)`。
- Produces: 每个出站 `Resp` 被写为标准 RESP 字节。
- Consumed by later tasks: `CommandHandler` 的 `ctx.writeAndFlush(response)` 会向前经过该编码器。

- [ ] **Step 1: 写编码器失败测试**

新建 `RespEncoderTest.java`：

```java
package cn.twopair.server.codec;

import cn.twopair.resp.SimpleString;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class RespEncoderTest {

    @Test
    public void testEncode() {
        EmbeddedChannel channel = new EmbeddedChannel(new RespEncoder());

        Assert.assertTrue(channel.writeOutbound(new SimpleString("PONG")));
        ByteBuf buffer = channel.readOutbound();
        try {
            Assert.assertEquals("+PONG\r\n", buffer.toString(StandardCharsets.UTF_8));
        } finally {
            buffer.release();
            channel.finishAndReleaseAll();
        }
    }
}
```

- [ ] **Step 2: 运行测试，确认缺少编码器而失败**

Run:

```bash
mvn -Dtest=RespEncoderTest test
```

Expected: 测试编译失败，错误包含 `cannot find symbol` 和 `RespEncoder`。

- [ ] **Step 3: 实现编码器**

新建 `RespEncoder.java`。不要复制 `Resp.encode` 的 `instanceof` 分支，保持协议规则只有一份。

```java
package cn.twopair.server.codec;

import cn.twopair.resp.Resp;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

public class RespEncoder extends MessageToByteEncoder<Resp> {

    @Override
    protected void encode(ChannelHandlerContext ctx, Resp resp, ByteBuf out) {
        Resp.encode(resp, out);
    }
}
```

- [ ] **Step 4: 运行编码器测试**

Run:

```bash
mvn -Dtest=RespEncoderTest test
```

Expected: `Tests run: 1, Failures: 0, Errors: 0`。

- [ ] **Step 5: 提交出站编码器**

```bash
git add src/main/java/cn/twopair/server/codec/RespEncoder.java src/test/java/cn/twopair/server/codec/RespEncoderTest.java
git commit -m "feat(server): add RESP encoder"
```

### Task 4: 将 RESP 请求接到命令分发

**Files:**

- Create: `src/main/java/cn/twopair/server/handler/CommandHandler.java`
- Create: `src/test/java/cn/twopair/server/handler/CommandHandlerTest.java`
- Modify: `src/main/java/cn/twopair/command/CommandFactory.java`
- Modify: `src/test/java/cn/twopair/command/CommandFactoryTest.java`

**Interfaces:**

- Consumes: `RespArray`、`CommandFactory.from(RespArray)`、`RedisCore`。
- Produces: 正常命令的响应 `Resp`；命令输入错误的 `Errors`；协议异常的 `Errors` 后关闭连接。
- Consumed by later tasks: `RedisServer` 注入共享 `RedisCore` 并把该 Handler 放在 `RespEncoder` 之后。

- [ ] **Step 1: 先写命令 Handler 与 NIL 命令数组的失败测试**

新建 `CommandHandlerTest.java`。`RespEncoder` 必须放在 `CommandHandler` 前面，才能使 Handler 写出的响应被编码。

```java
package cn.twopair.server.handler;

import cn.twopair.core.RedisCore;
import cn.twopair.core.impl.RedisCoreImpl;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import cn.twopair.resp.RespArray;
import cn.twopair.server.codec.RespEncoder;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class CommandHandlerTest {

    @Test
    public void testHandleCommandAndKeepChannelUsableAfterCommandError() {
        RedisCore redisCore = new RedisCoreImpl();
        EmbeddedChannel channel = new EmbeddedChannel(new RespEncoder(), new CommandHandler(redisCore));

        channel.writeInbound(command("PING"));
        Assert.assertEquals("+PONG\r\n", readOutbound(channel));

        channel.writeInbound(command("SET", "name", "twopair"));
        Assert.assertEquals("+OK\r\n", readOutbound(channel));

        channel.writeInbound(command("GET", "name"));
        Assert.assertEquals("$7\r\ntwopair\r\n", readOutbound(channel));

        channel.writeInbound(command("UNKNOWN"));
        Assert.assertEquals("-ERR 不支持的命令: UNKNOWN\r\n", readOutbound(channel));
        Assert.assertTrue(channel.isOpen());

        channel.writeInbound(command("PING"));
        Assert.assertEquals("+PONG\r\n", readOutbound(channel));

        channel.finishAndReleaseAll();
    }

    private RespArray command(String... arguments) {
        Resp[] array = new Resp[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            array[i] = new BulkString(new BytesWrapper(arguments[i].getBytes(StandardCharsets.UTF_8)));
        }
        return new RespArray(array);
    }

    private String readOutbound(EmbeddedChannel channel) {
        ByteBuf buffer = channel.readOutbound();
        try {
            return buffer.toString(StandardCharsets.UTF_8);
        } finally {
            buffer.release();
        }
    }
}
```

在 `CommandFactoryTest.testUnsupportedCommand()` 的末尾追加 NIL 数组测试：

```java
try {
    CommandFactory.from(RespArray.NIL);
    Assert.fail("预期NIL命令数组应抛出 IllegalArgumentException");
} catch (IllegalArgumentException e) {
    Assert.assertEquals("命令数组不能为空", e.getMessage());
}
```

- [ ] **Step 2: 运行测试，确认缺少 Handler 和 NIL 防护而失败**

Run:

```bash
mvn -Dtest=CommandFactoryTest,CommandHandlerTest test
```

Expected: 测试编译失败，错误包含 `cannot find symbol` 和 `CommandHandler`；实现 Handler 前，NIL 测试也会因空指针而不能满足预期。

- [ ] **Step 3: 给 CommandFactory 补输入边界校验**

将 `CommandFactory.from` 开头两段校验改为以下形式。`BulkString.NIL` 不能作为命令名，否则调用 `toUtf8String()` 会空指针。

```java
public static Command from(RespArray respArray) {
    if (respArray == null || respArray.getArray() == null || respArray.getArray().length == 0) {
        throw new IllegalArgumentException("命令数组不能为空");
    }

    Resp[] array = respArray.getArray();
    if (!(array[0] instanceof BulkString) || ((BulkString) array[0]).getBytesWrapper() == null) {
        throw new IllegalArgumentException("命令名必须是非空BulkString");
    }

    String commandName = ((BulkString) array[0])
            .getBytesWrapper()
            .toUtf8String()
            .toUpperCase(Locale.ROOT);

    Supplier<Command> commandSupplier = COMMAND_MAP.get(commandName);
    if (commandSupplier == null) {
        throw new IllegalArgumentException("不支持的命令: " + commandName);
    }

    Command command = commandSupplier.get();
    command.setContent(array);
    return command;
}
```

- [ ] **Step 4: 实现业务 Handler**

新建 `CommandHandler.java`。业务异常不关闭连接，让客户端可以继续发送合法命令；协议异常由 `exceptionCaught` 返回错误后关闭连接。

```java
package cn.twopair.server.handler;

import cn.twopair.command.Command;
import cn.twopair.command.CommandFactory;
import cn.twopair.core.RedisCore;
import cn.twopair.resp.Errors;
import cn.twopair.resp.Resp;
import cn.twopair.resp.RespArray;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

public class CommandHandler extends SimpleChannelInboundHandler<Resp> {

    private final RedisCore redisCore;

    public CommandHandler(RedisCore redisCore) {
        this.redisCore = redisCore;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Resp resp) {
        if (!(resp instanceof RespArray)) {
            writeError(ctx, "命令必须使用 RESP Array");
            return;
        }

        try {
            Command command = CommandFactory.from((RespArray) resp);
            Resp response = command.handle(redisCore);
            ctx.writeAndFlush(response);
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? "命令执行失败" : e.getMessage();
            writeError(ctx, message);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Throwable rootCause = cause.getCause() == null ? cause : cause.getCause();
        String message = rootCause.getMessage();
        ChannelFuture future = writeError(ctx, "Protocol error" + (message == null ? "" : ": " + message));
        future.addListener(ChannelFutureListener.CLOSE);
    }

    private ChannelFuture writeError(ChannelHandlerContext ctx, String message) {
        return ctx.writeAndFlush(new Errors("ERR " + message));
    }
}
```

本阶段使用 `RuntimeException` 兜住已有 SET、GET 命令尚未细分的参数异常，防止畸形网络输入直接终止业务线程。后续扩展命令时，每个具体命令仍应把参数校验收敛到自己的 `setContent` 中。

- [ ] **Step 5: 运行命令层测试**

Run:

```bash
mvn -Dtest=CommandFactoryTest,CommandHandlerTest test
```

Expected: `Tests run: 4, Failures: 0, Errors: 0`。

- [ ] **Step 6: 提交命令网络适配层**

```bash
git add src/main/java/cn/twopair/command/CommandFactory.java src/main/java/cn/twopair/server/handler/CommandHandler.java src/test/java/cn/twopair/command/CommandFactoryTest.java src/test/java/cn/twopair/server/handler/CommandHandlerTest.java
git commit -m "feat(server): add command request handler"
```

### Task 5: 启动 TCP 服务并完成真实连接测试

**Files:**

- Create: `src/main/java/cn/twopair/server/RedisServer.java`
- Create: `src/test/java/cn/twopair/server/RedisServerTest.java`
- Modify: `src/main/java/cn/twopair/Main.java`

**Interfaces:**

- Produces: `new RedisServer()` 监听 `6378`；`new RedisServer(0)` 监听操作系统分配的临时端口。
- Produces: `start()`、`getPort()`、`blockUntilShutdown()`、`stop()` 生命周期方法。
- Consumes: `RespDecoder`、`RespEncoder`、`CommandHandler` 和唯一的 `RedisCoreImpl`。

- [ ] **Step 1: 写真实本地 Socket 的失败集成测试**

新建 `RedisServerTest.java`。测试不依赖本机安装的 Redis，通过端口 `0` 启动 MiniRedis，再用两个 TCP 客户端证明共享存储。

```java
package cn.twopair.server;

import org.junit.Assert;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class RedisServerTest {

    @Test
    public void testStartAndShareDataBetweenConnections() throws IOException {
        RedisServer server = new RedisServer(0);
        server.start();

        try (
                Socket firstClient = new Socket("127.0.0.1", server.getPort());
                Socket secondClient = new Socket("127.0.0.1", server.getPort())
        ) {
            OutputStream firstOutput = firstClient.getOutputStream();
            BufferedReader firstInput = new BufferedReader(new InputStreamReader(firstClient.getInputStream(), StandardCharsets.UTF_8));
            firstOutput.write("*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.UTF_8));
            firstOutput.flush();
            Assert.assertEquals("+PONG", firstInput.readLine());

            firstOutput.write("*3\r\n$3\r\nSET\r\n$4\r\nname\r\n$7\r\ntwopair\r\n".getBytes(StandardCharsets.UTF_8));
            firstOutput.flush();
            Assert.assertEquals("+OK", firstInput.readLine());

            OutputStream secondOutput = secondClient.getOutputStream();
            BufferedReader secondInput = new BufferedReader(new InputStreamReader(secondClient.getInputStream(), StandardCharsets.UTF_8));
            secondOutput.write("*2\r\n$3\r\nGET\r\n$4\r\nname\r\n".getBytes(StandardCharsets.UTF_8));
            secondOutput.flush();
            Assert.assertEquals("$7", secondInput.readLine());
            Assert.assertEquals("twopair", secondInput.readLine());
        } finally {
            server.stop();
        }
    }
}
```

- [ ] **Step 2: 运行测试，确认缺少服务类而失败**

Run:

```bash
mvn -Dtest=RedisServerTest test
```

Expected: 测试编译失败，错误包含 `cannot find symbol` 和 `RedisServer`。

- [ ] **Step 3: 实现服务生命周期和 Pipeline 组装**

新建 `RedisServer.java`。`start()` 只完成绑定，便于自动化测试；`blockUntilShutdown()` 只在应用入口阻塞。`stop()` 可以重复调用，保证测试 `finally` 和 JVM shutdown hook 都能安全释放资源。

```java
package cn.twopair.server;

import cn.twopair.core.RedisCore;
import cn.twopair.core.impl.RedisCoreImpl;
import cn.twopair.server.codec.RespDecoder;
import cn.twopair.server.codec.RespEncoder;
import cn.twopair.server.handler.CommandHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.net.InetSocketAddress;

public class RedisServer {

    public static final int DEFAULT_PORT = 6378;

    private final int port;
    private final RedisCore redisCore;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public RedisServer() {
        this(DEFAULT_PORT);
    }

    public RedisServer(int port) {
        this.port = port;
        this.redisCore = new RedisCoreImpl();
    }

    public void start() {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel socketChannel) {
                            socketChannel.pipeline()
                                    .addLast(new RespDecoder())
                                    .addLast(new RespEncoder())
                                    .addLast(new CommandHandler(redisCore));
                        }
                    });

            serverChannel = bootstrap.bind(port).sync().channel();
            System.out.println("MiniRedis started at port " + getPort());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop();
            throw new IllegalStateException("Redis服务启动被中断", e);
        } catch (RuntimeException e) {
            stop();
            throw e;
        }
    }

    public int getPort() {
        if (serverChannel == null) {
            throw new IllegalStateException("Redis服务尚未启动");
        }
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    public void blockUntilShutdown() {
        if (serverChannel == null) {
            throw new IllegalStateException("Redis服务尚未启动");
        }
        try {
            serverChannel.closeFuture().sync();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void stop() {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().syncUninterruptibly();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().syncUninterruptibly();
        }
    }
}
```

将 `Main.java` 替换为以下入口：

```java
package cn.twopair;

import cn.twopair.server.RedisServer;

public class Main {

    public static void main(String[] args) {
        RedisServer server = new RedisServer();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

        try {
            server.start();
            server.blockUntilShutdown();
        } finally {
            server.stop();
        }
    }
}
```

- [ ] **Step 4: 运行真实 TCP 集成测试和全部回归测试**

Run:

```bash
mvn -Dtest=RedisServerTest test
mvn test
```

Expected: `RedisServerTest` 通过，完整测试没有 Failures 或 Errors。

- [ ] **Step 5: 用 redis-cli 完成手工验收**

先在 IDE 运行 `Main`，看到 `MiniRedis started at port 6378` 后，在另一个终端执行：

```bash
redis-cli -p 6378 ping
redis-cli -p 6378 set name twopair
redis-cli -p 6378 get name
```

Expected:

```text
PONG
OK
twopair
```

再打开第二个终端，执行：

```bash
redis-cli -p 6378 get name
```

Expected: `twopair`。这验证了不同 TCP 连接共享同一个 `RedisCore`。

- [ ] **Step 6: 提交可运行服务端**

```bash
git add src/main/java/cn/twopair/server/RedisServer.java src/main/java/cn/twopair/Main.java src/test/java/cn/twopair/server/RedisServerTest.java
git commit -m "feat(server): start MiniRedis with Netty"
```

## 最终验收清单

- [ ] `mvn test` 全部通过。
- [ ] `RespDecoderTest` 同时覆盖拆包和粘包。
- [ ] `RespEncoderTest` 验证 `Resp` 可以写回标准字节。
- [ ] `CommandHandlerTest` 验证 PING、SET、GET、未知命令与错误后的连接复用。
- [ ] `RedisServerTest` 验证真实 TCP 服务和跨连接共享数据。
- [ ] `redis-cli -p 6378` 的 PING、SET、GET 手工验证通过。
