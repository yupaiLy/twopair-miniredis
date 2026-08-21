package cn.twopair.command.impl.set;

import cn.twopair.command.CommandType;
import cn.twopair.command.impl.AbstractCollectionScan;
import cn.twopair.core.RedisCore;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;

import java.util.List;

/**
 * 实现SSCAN命令，分页扫描Set中的成员。
 *
 * @author ljj
 */
public class SScan extends AbstractCollectionScan<BytesWrapper> {

	/**
	 * {@inheritDoc}
	 */
	@Override
	public CommandType type() {
		return CommandType.SSCAN;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected List<BytesWrapper> getItems(RedisCore redisCore, BytesWrapper key) {
		return redisCore.scanSetMembers(key);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected String getMatchText(BytesWrapper item) {
		return item.toUtf8String();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected Resp[] encodeItem(BytesWrapper item) {
		return new Resp[]{new BulkString(item)};
	}
}
