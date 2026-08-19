package cn.twopair.command;

import cn.twopair.command.impl.*;
import cn.twopair.command.impl.hash.HDel;
import cn.twopair.command.impl.hash.HGet;
import cn.twopair.command.impl.hash.HLen;
import cn.twopair.command.impl.hash.HScan;
import cn.twopair.command.impl.hash.HSet;
import cn.twopair.command.impl.list.LLen;
import cn.twopair.command.impl.list.LPop;
import cn.twopair.command.impl.list.LPush;
import cn.twopair.command.impl.list.LRange;
import cn.twopair.command.impl.set.SAdd;
import cn.twopair.command.impl.set.SCard;
import cn.twopair.command.impl.set.SIsMember;
import cn.twopair.command.impl.set.SRem;
import cn.twopair.command.impl.set.SScan;
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
 * @author ljj
 * @description Redis命令工厂
 * @date 2026/7/10
 * @twopair
 */
public class CommandFactory {

	/**
	 * 命令注册表。
	 *
	 * <p>key 是 Redis 命令名，例如 PING、SET、GET。
	 * value 是 Command 的创建器，而不是 Command 实例本身。
	 *
	 * <p>这里使用 Supplier 的原因：
	 * <ol>
	 *     <li>每次解析请求时都通过 Supplier#get() 创建新的 Command 对象；</li>
	 *     <li>Command 会通过 setContent(array) 保存本次请求参数，属于有状态对象；</li>
	 *     <li>如果 Map 中直接保存 Command 实例，不同请求会复用同一个对象，导致 content 被覆盖；</li>
	 *     <li>在并发请求下，复用同一个 Command 实例还可能造成线程安全问题；</li>
	 *     <li>使用 Supplier 可以替代大量 if-else / switch，新增命令时只需要注册一行。</li>
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
		COMMAND_MAP.put("LLEN", LLen::new);
		COMMAND_MAP.put("LRANGE", LRange::new);
		COMMAND_MAP.put("HSET", HSet::new);
		COMMAND_MAP.put("HGET", HGet::new);
		COMMAND_MAP.put("HDEL", HDel::new);
		COMMAND_MAP.put("HLEN", HLen::new);
		COMMAND_MAP.put("HSCAN", HScan::new);
		COMMAND_MAP.put("SADD", SAdd::new);
		COMMAND_MAP.put("SREM", SRem::new);
		COMMAND_MAP.put("SISMEMBER", SIsMember::new);
		COMMAND_MAP.put("SCARD", SCard::new);
		COMMAND_MAP.put("SSCAN", SScan::new);
		COMMAND_MAP.put("SCAN", Scan::new);
	}

	private CommandFactory() {
	}

	/**
	 * @author ljj
	 * @description 根据 RESP 数组创建本次请求对应的 Redis 命令对象。
	 * @date 2026/7/14
	 * @twopair
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
	 * Validates the first element of the provided RESP array as a command name and returns the
	 * command name in uppercase UTF-8 format.
	 *
	 * @param array the array of RESP objects to validate, where the first element is expected
	 *              to be a BulkString containing the command name.
	 * @return the validated command name in uppercase UTF-8 format.
	 * @throws IllegalArgumentException if the first element of the array is not a BulkString,
	 *                                  or if the command name is null, empty, or invalid.
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
