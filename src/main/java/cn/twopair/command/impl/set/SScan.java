package cn.twopair.command.impl.set;

import cn.twopair.command.CommandType;
import cn.twopair.command.impl.AbstractCollectionScan;
import cn.twopair.core.RedisCore;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;

import java.util.List;

/**
 * @author ljj
 * @description 实现SSCAN命令，分页扫描Set中的成员。
 * @date 2026/8/19
 * @twopair
 */
public class SScan extends AbstractCollectionScan<BytesWrapper> {

	@Override
	public CommandType type() {
		return CommandType.SSCAN;
	}

	@Override
	protected List<BytesWrapper> getItems(RedisCore redisCore, BytesWrapper key) {
		return redisCore.scanSetMembers(key);
	}

	@Override
	protected String getMatchText(BytesWrapper item) {
		return item.toUtf8String();
	}

	@Override
	protected Resp[] encodeItem(BytesWrapper item) {
		return new Resp[]{new BulkString(item)};
	}
}
