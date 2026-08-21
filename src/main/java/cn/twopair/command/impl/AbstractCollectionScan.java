package cn.twopair.command.impl;

import cn.twopair.command.Command;
import cn.twopair.core.RedisCore;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.resp.BulkString;
import cn.twopair.resp.Resp;
import cn.twopair.resp.RespArray;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 封装HSCAN和SSCAN共享的参数解析、游标分页与MATCH过滤流程。
 *
 * @author ljj
 */
public abstract class AbstractCollectionScan<T> implements Command {

	private BytesWrapper key;
	private long cursor;
	private String matchPattern = "*";
	private long count = 10L;

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void setContent(Resp[] array) {
		String commandName = type().name();

		if (array == null || array.length < 3) {
			throw new IllegalArgumentException(commandName + "命令需要key和cursor参数");
		}

		for (int i = 1; i < array.length; i++) {
			if (!(array[i] instanceof BulkString bulkString)
					|| bulkString.getBytesWrapper() == null
					|| bulkString.getBytesWrapper().getByteArray() == null) {
				throw new IllegalArgumentException(commandName + "的key、cursor和选项必须是BulkString");
			}
		}

		if ((array.length - 3) % 2 != 0) {
			throw new IllegalArgumentException(commandName + "选项必须成对出现");
		}

		BytesWrapper parsedKey = ((BulkString) array[1]).getBytesWrapper();
		long parsedCursor = parseCursor(text(array[2]), commandName);
		String parsedPattern = "*";
		long parsedCount = 10L;

		for (int i = 3; i < array.length; i += 2) {
			String option = text(array[i]).toUpperCase(Locale.ROOT);
			String value = text(array[i + 1]);

			if ("MATCH".equals(option)) {
				parsedPattern = value;
			} else if ("COUNT".equals(option)) {
				parsedCount = parseCount(value, commandName);
			} else {
				throw new IllegalArgumentException("不支持的" + commandName + "选项: " + option);
			}
		}

		this.key = parsedKey;
		this.cursor = parsedCursor;
		this.matchPattern = parsedPattern;
		this.count = parsedCount;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Resp handle(RedisCore redisCore) {
		List<T> matchedItems = new ArrayList<>();

		for (T item : getItems(redisCore, key)) {
			if (globMatches(matchPattern, getMatchText(item))) {
				matchedItems.add(item);
			}
		}

		if (cursor >= matchedItems.size()) {
			return response(0L, List.of());
		}

		long remaining = matchedItems.size() - cursor;
		int fromIndex = (int) cursor;
		int pageSize = (int) Math.min(count, remaining);
		int toIndex = fromIndex + pageSize;
		long nextCursor = toIndex >= matchedItems.size() ? 0L : toIndex;

		return response(nextCursor, matchedItems.subList(fromIndex, toIndex));
	}

	/**
	 * 获取需要扫描的全量条目。
	 *
	 * <p>基类负责MATCH过滤和游标分页，子类只需返回目标集合的完整快照。
	 *
	 * @param redisCore {@link RedisCore Redis核心存储}
	 * @param key       需要扫描的key
	 * @return 按固定顺序排列的全量条目
	 */
	protected abstract List<T> getItems(RedisCore redisCore, BytesWrapper key);

	/**
	 * 获取条目中参与MATCH匹配的文本。
	 *
	 * @param item 需要匹配的条目
	 * @return 用于匹配的文本，例如Hash条目只返回field
	 */
	protected abstract String getMatchText(T item);

	/**
	 * 将条目编码为{@link Resp RESP响应}元素。
	 *
	 * @param item 需要编码的条目
	 * @return 该条目对应的{@link Resp RESP响应}对象数组，例如Hash条目返回field和value两个元素
	 */
	protected abstract Resp[] encodeItem(T item);

	/**
	 * 将下一游标和当前页条目编码为SCAN族命令的标准两元素响应。
	 *
	 * @param nextCursor 下一次扫描使用的游标，0表示本轮遍历结束
	 * @param items      当前页需要返回的条目
	 * @return 包含下一游标和当前页数据的{@link RespArray RESP数组}
	 */
	private RespArray response(long nextCursor, List<T> items) {
		List<Resp> encodedItems = new ArrayList<>();

		for (T item : items) {
			for (Resp resp : encodeItem(item)) {
				encodedItems.add(resp);
			}
		}

		return new RespArray(new Resp[]{
				new BulkString(new BytesWrapper(String.valueOf(nextCursor).getBytes(StandardCharsets.UTF_8))),
				new RespArray(encodedItems.toArray(new Resp[0]))
		});
	}

	/**
	 * 解析并校验SCAN族命令的游标。
	 *
	 * @param value       客户端传入的游标文本
	 * @param commandName 当前命令名称，用于构造错误信息
	 * @return 非负游标
	 * @throws IllegalArgumentException 游标不是非负整数时抛出
	 */
	private long parseCursor(String value, String commandName) {
		try {
			long parsedCursor = Long.parseLong(value);
			if (parsedCursor < 0L) {
				throw new NumberFormatException("游标不能为负数");
			}
			return parsedCursor;
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(commandName + "的cursor必须是非负整数", e);
		}
	}

	/**
	 * 解析并校验SCAN族命令的COUNT选项。
	 *
	 * @param value       客户端传入的COUNT文本
	 * @param commandName 当前命令名称，用于构造错误信息
	 * @return 大于0的期望返回数量
	 * @throws IllegalArgumentException COUNT不是正整数时抛出
	 */
	private long parseCount(String value, String commandName) {
		try {
			long parsedCount = Long.parseLong(value);
			if (parsedCount <= 0L) {
				throw new NumberFormatException("COUNT必须大于0");
			}
			return parsedCount;
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(commandName + "的COUNT必须是正整数", e);
		}
	}

	/**
	 * 将已校验的{@link BulkString 块字符串}参数转换为UTF-8文本。
	 *
	 * @param resp 已校验为非空{@link BulkString 块字符串}的{@link Resp RESP参数}
	 * @return 参数的UTF-8文本
	 */
	private String text(Resp resp) {
		return ((BulkString) resp).getBytesWrapper().toUtf8String();
	}

	/**
	 * 使用Redis SCAN支持的问号和星号通配规则匹配文本。
	 *
	 * @param pattern MATCH选项传入的匹配模式
	 * @param value   需要匹配的文本
	 * @return 文本符合模式时返回 {@code true}
	 */
	private boolean globMatches(String pattern, String value) {
		int patternIndex = 0;
		int valueIndex = 0;
		int starIndex = -1;
		int retryValueIndex = -1;

		while (valueIndex < value.length()) {
			if (patternIndex < pattern.length()
					&& (pattern.charAt(patternIndex) == '?'
					|| pattern.charAt(patternIndex) == value.charAt(valueIndex))) {
				patternIndex++;
				valueIndex++;
			} else if (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
				starIndex = patternIndex++;
				retryValueIndex = valueIndex;
			} else if (starIndex != -1) {
				patternIndex = starIndex + 1;
				valueIndex = ++retryValueIndex;
			} else {
				return false;
			}
		}

		while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
			patternIndex++;
		}

		return patternIndex == pattern.length();
	}
}
