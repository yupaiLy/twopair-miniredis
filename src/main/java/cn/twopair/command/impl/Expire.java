package cn.twopair.command.impl;

import cn.twopair.command.CommandType;
import cn.twopair.command.WriteCommand;
import cn.twopair.core.RedisCore;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.datatype.RedisData;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import cn.twopair.resp.RespArray;
import cn.twopair.resp.RespInt;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 为指定key设置过期秒数的EXPIRE命令。
 *
 * @author ljj
 */
public class Expire implements WriteCommand {

	private BytesWrapper key;
	private long seconds;

	/**
	 * {@inheritDoc}
	 */
	@Override
	public CommandType type() {
		return CommandType.EXPIRE;
	}

	/**
	 * 解析EXPIRE命令参数。
	 *
	 * @param array 命令数组，格式为EXPIRE key seconds
	 * @throws IllegalArgumentException 当参数数量、类型或内容不合法时抛出
	 */
	@Override
	public void setContent(Resp[] array) {
		if (array == null || array.length != 3) {
			throw new IllegalArgumentException(
					"EXPIRE命令需要key和seconds两个参数"
			);
		}

		if (!(array[1] instanceof BulkString keyBulkString)
				|| !(array[2] instanceof BulkString secondsBulkString)) {
			throw new IllegalArgumentException(
					"EXPIRE的key和seconds必须是BulkString"
			);
		}

		BytesWrapper parsedKey = keyBulkString.getBytesWrapper();
		long parsedSeconds = validateAndParseSeconds(parsedKey, secondsBulkString);
		// 所有参数验证成功后再修改命令对象，避免保存一半有效的状态。
		this.key = parsedKey;
		this.seconds = parsedSeconds;
	}

	/**
	 * 校验key和seconds参数，并把seconds解析为 {@code long}。
	 *
	 * @param parsedKey        解析出的key，不能为 {@code null} 且内部字节数组不能为 {@code null}
	 * @param secondsBulkString 秒数参数，不能为 {@code null} 且内部内容不能为 {@code null}
	 * @return 解析得到的秒数
	 * @throws IllegalArgumentException 当key为空值、seconds为空值或不是合法整数时抛出
	 */
	private static long validateAndParseSeconds(BytesWrapper parsedKey, BulkString secondsBulkString) {
		BytesWrapper secondsBytes = secondsBulkString.getBytesWrapper();

		if (parsedKey == null || parsedKey.getByteArray() == null) {
			throw new IllegalArgumentException("EXPIRE的key不能是NIL");
		}

		if (secondsBytes == null || secondsBytes.getByteArray() == null) {
			throw new IllegalArgumentException(
					"EXPIRE的seconds必须是整数"
			);
		}

		long parsedSeconds;
		try {
			parsedSeconds = Long.parseLong(secondsBytes.toUtf8String());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(
					"EXPIRE的seconds必须是整数",
					e
			);
		}
		return parsedSeconds;
	}

	/**
	 * 将成功设置TTL的EXPIRE转换成绝对时间PEXPIREAT。
	 *
	 * @param originalCommand 原始EXPIRE命令
	 * @param redisCore       命令执行后的Redis核心存储
	 * @return 适合写入AOF的命令
	 */
	@Override
	public List<RespArray> toAofCommands(RespArray originalCommand, RedisCore redisCore) {
		RedisData redisData = redisCore.get(key);

		/*
		 * key不存在或已被非正数EXPIRE删除时，保留原始命令，
		 * 以便重放时维持删除或空操作语义。
		 */
		if (redisData == null || redisData.timeout() == -1L) {
			return List.of(originalCommand);
		}

		RespArray expireAtCommand = new RespArray(new Resp[]{
				new BulkString(new BytesWrapper(
						"PEXPIREAT".getBytes(StandardCharsets.UTF_8)
				)),
				new BulkString(key),
				new BulkString(new BytesWrapper(
						String.valueOf(redisData.timeout())
								.getBytes(StandardCharsets.UTF_8)
				))
		});

		return List.of(expireAtCommand);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Resp handle(RedisCore redisCore) {
		boolean success = redisCore.expire(key, seconds);
		return new RespInt(success ? 1L : 0L);
	}
}
