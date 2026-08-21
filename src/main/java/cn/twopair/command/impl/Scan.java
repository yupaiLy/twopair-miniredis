package cn.twopair.command.impl;

import cn.twopair.command.Command;
import cn.twopair.command.CommandType;
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
 * 实现SCAN命令，支持游标分页、MATCH过滤和COUNT数量提示。
 *
 * @author ljj
 */
public class Scan implements Command {

	private long cursor;
	private String matchPattern = "*";
	private long count = 10L;

	/**
	 * {@inheritDoc}
	 */
	@Override
	public CommandType type() {
		return CommandType.SCAN;
	}

	/**
	 * 解析SCAN命令参数。
	 *
	 * @param array 命令数组，格式为SCAN cursor [MATCH pattern] [COUNT count]
	 * @throws IllegalArgumentException 当参数数量、类型或内容不合法时抛出
	 */
	@Override
	public void setContent(Resp[] array) {
		if (array == null || array.length < 2) {
			throw new IllegalArgumentException("SCAN命令需要cursor参数");
		}

		for (int i = 1; i < array.length; i++) {
			if (!(array[i] instanceof BulkString bulkString)
					|| bulkString.getBytesWrapper() == null
					|| bulkString.getBytesWrapper().getByteArray() == null) {
				throw new IllegalArgumentException("SCAN的cursor和选项必须是BulkString");
			}
		}

		long parsedCursor = parseCursor(((BulkString) array[1]).getBytesWrapper());
		if ((array.length - 2) % 2 != 0) {
			throw new IllegalArgumentException("SCAN选项必须成对出现");
		}

		String parsedPattern = "*";
		long parsedCount = 10L;

		for (int i = 2; i < array.length; i += 2) {
			String option = text(array[i]).toUpperCase(Locale.ROOT);
			String value = text(array[i + 1]);

			if ("MATCH".equals(option)) {
				parsedPattern = value;
			} else if ("COUNT".equals(option)) {
				parsedCount = parseCount(value);
			} else {
				throw new IllegalArgumentException("不支持的SCAN选项: " + option);
			}
		}

		// 所有参数都验证通过后再修改字段，避免命令对象处于半初始化状态。
		this.cursor = parsedCursor;
		this.matchPattern = parsedPattern;
		this.count = parsedCount;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Resp handle(RedisCore redisCore) {
		List<BytesWrapper> matchedKeys = new ArrayList<>();

		for (BytesWrapper key : redisCore.scanKeys()) {
			if (globMatches(matchPattern, key.toUtf8String())) {
				matchedKeys.add(key);
			}
		}

		if (cursor >= matchedKeys.size()) {
			return response(0L, List.of());
		}

		long remaining = matchedKeys.size() - cursor;
		int fromIndex = (int) cursor;
		int pageSize = (int) Math.min(count, remaining);
		int toIndex = fromIndex + pageSize;
		long nextCursor = toIndex >= matchedKeys.size() ? 0L : toIndex;

		return response(nextCursor, matchedKeys.subList(fromIndex, toIndex));
	}

	/**
	 * 解析非负游标。
	 *
	 * @param bytes 游标字节
	 * @return 游标数值
	 */
	private long parseCursor(BytesWrapper bytes) {
		try {
			long value = Long.parseLong(bytes.toUtf8String());
			if (value < 0L) {
				throw new NumberFormatException("游标不能为负数");
			}
			return value;
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("SCAN的cursor必须是非负整数", e);
		}
	}

	/**
	 * 解析正整数COUNT。
	 *
	 * @param value COUNT文本
	 * @return COUNT数值
	 */
	private long parseCount(String value) {
		try {
			long parsedCount = Long.parseLong(value);
			if (parsedCount <= 0L) {
				throw new NumberFormatException("COUNT必须大于0");
			}
			return parsedCount;
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("SCAN的COUNT必须是正整数", e);
		}
	}

	/**
	 * 创建SCAN标准响应：[nextCursor, [key...]]。
	 *
	 * @param nextCursor 下一页游标，0表示扫描结束
	 * @param keys       本页key
	 * @return {@link RespArray RESP数组}响应
	 */
	private RespArray response(long nextCursor, List<BytesWrapper> keys) {
		Resp[] keyArray = new Resp[keys.size()];
		for (int i = 0; i < keys.size(); i++) {
			keyArray[i] = new BulkString(keys.get(i));
		}

		return new RespArray(new Resp[]{
				new BulkString(new BytesWrapper(String.valueOf(nextCursor).getBytes(StandardCharsets.UTF_8))),
				new RespArray(keyArray)
		});
	}

	/**
	 * 将{@link BulkString 块字符串}转换成UTF-8文本。
	 *
	 * @param resp {@link BulkString 块字符串}参数
	 * @return UTF-8文本
	 */
	private String text(Resp resp) {
		return ((BulkString) resp).getBytesWrapper().toUtf8String();
	}

	/**
	 * 匹配SCAN的简单glob表达式，支持星号和问号通配符。
	 *
	 * @param pattern 匹配表达式
	 * @param value   key文本
	 * @return 匹配成功时返回 {@code true}
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
