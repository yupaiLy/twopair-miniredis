package cn.twopair.command.impl;

import cn.twopair.command.Command;
import cn.twopair.command.CommandType;
import cn.twopair.core.RedisCore;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import cn.twopair.resp.SimpleString;

/**
 * 兼容客户端发送的SELECT 0命令，当前项目仅支持默认数据库。
 *
 * @author ljj
 */
public class Select implements Command {

	private long database;

	/**
	 * {@inheritDoc}
	 */
	@Override
	public CommandType type() {
		return CommandType.SELECT;
	}

	/**
	 * 解析SELECT命令参数。
	 *
	 * @param array 命令数组，格式为SELECT database
	 * @throws IllegalArgumentException 当参数数量、类型或内容不合法时抛出
	 */
	@Override
	public void setContent(Resp[] array) {
		if (array == null || array.length != 2) {
			throw new IllegalArgumentException("SELECT命令需要database一个参数");
		}

		if (!(array[1] instanceof BulkString databaseBulkString)) {
			throw new IllegalArgumentException("SELECT的database必须是BulkString");
		}

		BytesWrapper databaseBytes = databaseBulkString.getBytesWrapper();

		if (databaseBytes == null || databaseBytes.getByteArray() == null) {
			throw new IllegalArgumentException("SELECT的database必须是整数");
		}

		long parsedDatabase;

		try {
			parsedDatabase = Long.parseLong(databaseBytes.toUtf8String());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("SELECT的database必须是整数", e);
		}

		// 当前RedisCore只有一份顶层存储，只能诚实支持默认数据库0。
		if (parsedDatabase != 0L) {
			throw new IllegalArgumentException("当前仅支持database 0");
		}

		this.database = parsedDatabase;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Resp handle(RedisCore redisCore) {
		// database已经在参数解析阶段验证为0，因此这里不需要切换存储。
		return new SimpleString("OK");
	}
}
