package cn.twopair.persistence.aof;

import cn.twopair.core.RedisCore;
import cn.twopair.datatype.*;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import cn.twopair.resp.RespArray;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 根据Redis当前内存状态生成AOF Rewrite恢复命令。
 *
 * @author ljj
 */
public final class AofRewriteCommandBuilder {
	/**
	 * 单条AOF Rewrite命令最多包含的集合元素数量，与Redis官方实现保持一致。
	 */
	private static final int MAX_ITEMS_PER_COMMAND = 64;

	/**
	 * 工具类不允许创建实例。
	 */
	private AofRewriteCommandBuilder() {
	}

	/**
	 * 将Redis当前内存状态转换为可以完整恢复数据的RESP命令。
	 *
	 * <p>当前支持String、List、Hash和Set类型。带过期时间的数据会在数据恢复命令后追加PEXPIREAT，
	 * 使用绝对毫秒时间可以避免AOF重放时重新计算TTL。
	 *
	 * @param redisCore Redis核心存储
	 * @return 按key字节顺序排列的AOF恢复命令
	 * @throws NullPointerException  redisCore为null时抛出
	 * @throws IllegalStateException 遇到当前尚未支持的数据类型时抛出
	 */
	public static List<RespArray> build(RedisCore redisCore) {
		Objects.requireNonNull(redisCore, "RedisCore不能为空");
		List<RespArray> commands = new ArrayList<>();

		for (BytesWrapper key : redisCore.scanKeys()) {
			RedisData redisData = redisCore.get(key);

			// key可能在scanKeys之后过期，因此需要再次确认。
			if (redisData == null) {
				continue;
			}
			if (redisData instanceof RedisString redisString) {
				commands.add(command("SET", key, redisString.getValue()));
			} else if (redisData instanceof RedisList redisList) {
				appendListCommands(commands, key, redisList);
			} else if (redisData instanceof RedisHash redisHash) {
				appendHashCommands(commands, key, redisHash);
			} else if (redisData instanceof RedisSet redisSet) {
				appendSetCommands(commands, key, redisSet);
			} else {
				throw new IllegalStateException("AOF Rewrite暂不支持的数据类型: " + redisData.getClass().getName());
			}

			if (redisData.timeout() != -1L) {
				commands.add(command("PEXPIREAT", key, bytes(String.valueOf(redisData.timeout()))));
			}
		}

		return List.copyOf(commands);
	}

	/**
	 * 按照Redis官方Rewrite方式，将Set成员分批转换为SADD命令。
	 *
	 * <p>Set没有顺序语义，但使用有序快照可以保证Rewrite结果稳定。
	 *
	 * @param commands 接收AOF恢复命令的列表
	 * @param key      Set的key
	 * @param redisSet Set数据
	 */
	private static void appendSetCommands(List<RespArray> commands, BytesWrapper key, RedisSet redisSet) {
		List<BytesWrapper> members = redisSet.membersSnapshot();

		for (int start = 0; start < members.size(); start += MAX_ITEMS_PER_COMMAND) {
			int end = Math.min(start + MAX_ITEMS_PER_COMMAND, members.size());
			BytesWrapper[] arguments = new BytesWrapper[end - start + 1];
			arguments[0] = key;

			for (int i = start; i < end; i++) {
				arguments[i - start + 1] = members.get(i);
			}

			commands.add(command("SADD", arguments));
		}
	}

	/**
	 * 按照Redis官方Rewrite方式，将Hash字段和值分批转换为HMSET命令。
	 *
	 * <p>每条命令最多恢复64个Hash条目。一个条目包含field和value两个参数，
	 * 因此一条完整命令的格式为HMSET key field value [field value ...]。
	 *
	 * @param commands  接收AOF恢复命令的列表
	 * @param key       Hash的key
	 * @param redisHash Hash数据
	 */
	private static void appendHashCommands(List<RespArray> commands, BytesWrapper key, RedisHash redisHash) {
		List<Map.Entry<BytesWrapper, BytesWrapper>> entries = redisHash.entriesSnapshot();

		for (int start = 0; start < entries.size(); start += MAX_ITEMS_PER_COMMAND) {
			int end = Math.min(start + MAX_ITEMS_PER_COMMAND, entries.size());
			BytesWrapper[] arguments = new BytesWrapper[1 + (end - start) * 2];
			arguments[0] = key;
			int argumentIndex = 1;

			for (int i = start; i < end; i++) {
				Map.Entry<BytesWrapper, BytesWrapper> entry = entries.get(i);
				arguments[argumentIndex++] = entry.getKey();
				arguments[argumentIndex++] = entry.getValue();
			}

			commands.add(command("HMSET", arguments));
		}
	}

	/**
	 * 按照Redis官方Rewrite方式，将列表从头到尾分批转换为RPUSH命令。
	 *
	 * @param commands  接收AOF恢复命令的列表
	 * @param key       列表key
	 * @param redisList 列表数据
	 */
	private static void appendListCommands(List<RespArray> commands, BytesWrapper key, RedisList redisList) {
		List<BytesWrapper> elements = redisList.range(0L, -1L);

		for (int start = 0; start < elements.size(); start += MAX_ITEMS_PER_COMMAND) {
			int end = Math.min(start + MAX_ITEMS_PER_COMMAND, elements.size());
			BytesWrapper[] arguments = new BytesWrapper[end - start + 1];
			arguments[0] = key;

			for (int i = start; i < end; i++) {
				arguments[i - start + 1] = elements.get(i);
			}

			commands.add(command("RPUSH", arguments));
		}
	}

	/**
	 * 构造一条由BulkString组成的RESP数组命令。
	 *
	 * @param commandName 命令名称
	 * @param arguments   命令参数
	 * @return RESP数组命令
	 */
	private static RespArray command(String commandName, BytesWrapper... arguments) {
		Resp[] array = new Resp[arguments.length + 1];
		array[0] = bulkString(bytes(commandName));

		for (int i = 0; i < arguments.length; i++) {
			array[i + 1] = bulkString(arguments[i]);
		}

		return new RespArray(array);
	}

	/**
	 * 创建二进制安全的BulkString快照。
	 *
	 * @param value 原始字节内容
	 * @return 不共享原始字节数组的BulkString
	 */
	private static BulkString bulkString(BytesWrapper value) {
		Objects.requireNonNull(value, "AOF Rewrite参数不能为空");
		byte[] content = Objects.requireNonNull(value.getByteArray(), "AOF Rewrite参数内容不能为空");
		return new BulkString(new BytesWrapper(Arrays.copyOf(content, content.length)));
	}

	/**
	 * 将文本转换为项目统一UTF-8字节包装对象。
	 *
	 * @param value 文本内容
	 * @return UTF-8字节包装对象
	 */
	private static BytesWrapper bytes(String value) {
		return new BytesWrapper(value.getBytes(StandardCharsets.UTF_8));
	}
}
