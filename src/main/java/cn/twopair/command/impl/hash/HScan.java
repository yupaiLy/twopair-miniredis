package cn.twopair.command.impl.hash;

import cn.twopair.command.CommandType;
import cn.twopair.command.impl.AbstractCollectionScan;
import cn.twopair.core.RedisCore;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;

import java.util.List;
import java.util.Map;

/**
 * @author ljj
 * @description 实现HSCAN命令，分页扫描Hash中的field和value。
 * @date 2026/8/19
 * @twopair
 */
public class HScan extends AbstractCollectionScan<Map.Entry<BytesWrapper, BytesWrapper>> {

	@Override
	public CommandType type() {
		return CommandType.HSCAN;
	}

	@Override
	protected List<Map.Entry<BytesWrapper, BytesWrapper>> getItems(RedisCore redisCore, BytesWrapper key) {
		return redisCore.scanHashEntries(key);
	}

	@Override
	protected String getMatchText(Map.Entry<BytesWrapper, BytesWrapper> item) {
		// Redis的HSCAN MATCH只匹配field，不匹配value。
		return item.getKey().toUtf8String();
	}

	@Override
	protected Resp[] encodeItem(Map.Entry<BytesWrapper, BytesWrapper> item) {
		return new Resp[]{new BulkString(item.getKey()), new BulkString(item.getValue())};
	}
}
