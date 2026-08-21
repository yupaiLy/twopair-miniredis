package cn.twopair.command;

import cn.twopair.command.impl.*;
import cn.twopair.command.impl.hash.*;
import cn.twopair.command.impl.list.*;
import cn.twopair.command.impl.set.*;
import cn.twopair.command.impl.string.Get;
import cn.twopair.command.impl.string.Set;
import cn.twopair.command.impl.string.SetEx;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import cn.twopair.resp.RespArray;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Redis命令工厂
 *
 * @author ljj
 */
public class CommandFactory {

	/**
	 * 命令注册表。
	 *
	 * <p>key 是 Redis 命令名，例如 {@code PING}、{@code SET}、{@code GET}。
	 * value 是 {@code Command} 的创建器，而不是 {@code Command} 实例本身。
	 *
	 * <p>这里使用 {@code Supplier} 的原因：
	 * <ol>
	 *     <li>每次解析请求时都通过 {@code Supplier#get()} 创建新的 {@code Command} 对象；</li>
	 *     <li>{@code Command} 会通过 {@code setContent(array)} 保存本次请求参数，属于有状态对象；</li>
	 *     <li>如果 {@code Map} 中直接保存 {@code Command} 实例，不同请求会复用同一个对象，导致参数被覆盖；</li>
	 *     <li>在并发请求下，复用同一个 {@code Command} 实例还可能造成线程安全问题；</li>
	 *     <li>使用 {@code Supplier} 可以替代大量 {@code if-else} / {@code switch}，新增命令时只需要注册一行。</li>
	 * </ol>
	 */
	private static final Map<String, Supplier<Command>> COMMAND_MAP = new HashMap<>();

	static {
		// Ping::new 等价于 () -> new Ping()，表示每次调用都会创建新的 Ping 对象。
		COMMAND_MAP.put("PING", Ping::new);
		COMMAND_MAP.put("SET", Set::new);
		COMMAND_MAP.put("SETEX", SetEx::new);
		COMMAND_MAP.put("GET", Get::new);
		COMMAND_MAP.put("EXPIRE", Expire::new);
		COMMAND_MAP.put("PEXPIREAT", PExpireAt::new);
		COMMAND_MAP.put("TTL", Ttl::new);
		COMMAND_MAP.put("LPUSH", LPush::new);
		COMMAND_MAP.put("LPOP", LPop::new);
		COMMAND_MAP.put("RPUSH", RPush::new);
		COMMAND_MAP.put("RPOP", RPop::new);
		COMMAND_MAP.put("LLEN", LLen::new);
		COMMAND_MAP.put("LRANGE", LRange::new);
		COMMAND_MAP.put("HSET", HSet::new);
		COMMAND_MAP.put("HMSET", HMSet::new);
		COMMAND_MAP.put("HGET", HGet::new);
		COMMAND_MAP.put("HDEL", HDel::new);
		COMMAND_MAP.put("HLEN", HLen::new);
		COMMAND_MAP.put("HSCAN", HScan::new);
		COMMAND_MAP.put("SADD", SAdd::new);
		COMMAND_MAP.put("SREM", SRem::new);
		COMMAND_MAP.put("SISMEMBER", SIsMember::new);
		COMMAND_MAP.put("SCARD", SCard::new);
		COMMAND_MAP.put("SSCAN", SScan::new);
		COMMAND_MAP.put("SELECT", Select::new);
		COMMAND_MAP.put("SCAN", Scan::new);
		COMMAND_MAP.put("TYPE", Type::new);
		COMMAND_MAP.put("DEL", Del::new);
		COMMAND_MAP.put("BGREWRITEAOF", BgRewriteAof::new);
	}

	/**
	 * 工具类不允许创建实例。
	 */
	private CommandFactory() {
	}

	/**
	 * 根据 RESP 数组创建本次请求对应的 Redis 命令对象。
	 *
	 * @param respArray 客户端发送的 RESP 数组命令
	 * @return 完成参数注入的命令对象
	 * @throws IllegalArgumentException 当命令数组为空、命令名非法或命令不受支持时抛出
	 */
	public static Command from(RespArray respArray) {
		if (respArray == null
				|| respArray.getArray() == null
				|| respArray.getArray().length == 0) {
			throw new IllegalArgumentException("命令数组不能为空");
		}

		Resp[] array = respArray.getArray();
		String commandName = validateAndGetCommandName(array);
		Supplier<Command> commandSupplier = COMMAND_MAP.get(commandName);
		if (commandSupplier == null) {
			throw new IllegalArgumentException("不支持的命令: " + commandName);
		}

		// 通过 Supplier 创建本次请求专属的 Command 实例，避免多个请求共享同一个有状态对象。
		Command command = commandSupplier.get();

		// 将本次 RESP 请求内容设置到命令对象中，由具体命令在执行时解析参数。
		command.setContent(array);
		return command;
	}

	/**
	 * 校验 RESP 数组首元素并将其解析为命令名。
	 *
	 * @param array 客户端发送的 RESP 数组，首元素必须是 {@link BulkString}
	 * @return 转换为大写后的 UTF-8 命令名
	 * @throws IllegalArgumentException 当首元素不是 {@link BulkString}，
	 *                                  或命令名为 {@code null}、空内容时抛出
	 */
	private static String validateAndGetCommandName(Resp[] array) {
		if (!(array[0] instanceof BulkString commandNameBulkString)) {
			throw new IllegalArgumentException("命令名必须是BulkString");
		}

		BytesWrapper commandNameBytes = commandNameBulkString.getBytesWrapper();

		/*
		 * 同时防御三种无效命令名：
		 * 1. BulkString.NIL；
		 * 2. 人工构造的 BytesWrapper(null)；
		 * 3. RESP 中长度为 0 的 BulkString。
		 */
		if (commandNameBytes == null
				|| commandNameBytes.getByteArray() == null
				|| commandNameBytes.getByteArray().length == 0) {
			throw new IllegalArgumentException("命令名不能为空");
		}

		return commandNameBytes
				.toUtf8String()
				.toUpperCase(Locale.ROOT);
	}
}
