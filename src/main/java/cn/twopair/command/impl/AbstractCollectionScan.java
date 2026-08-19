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
 * @author ljj
 * @description 封装HSCAN和SSCAN共享的参数解析、游标分页与MATCH过滤流程。
 * @date 2026/8/19
 * @twopair
 */
public abstract class AbstractCollectionScan<T> implements Command {

	private BytesWrapper key;
	private long cursor;
	private String matchPattern = "*";
	private long count = 10L;

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

	protected abstract List<T> getItems(RedisCore redisCore, BytesWrapper key);

	protected abstract String getMatchText(T item);

	protected abstract Resp[] encodeItem(T item);

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

	private String text(Resp resp) {
		return ((BulkString) resp).getBytesWrapper().toUtf8String();
	}

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
