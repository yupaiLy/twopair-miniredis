package cn.twopair.datatype;

import java.util.*;

/**
 * @author ljj
 * @description Redis列表值对象，封装列表元素和过期时间。
 * @date 2026/8/18
 * @twopair
 */
public class RedisList implements RedisData {

	/**
	 * 使用双端队列支持列表头尾的高效插入和删除。
	 */
	private final Deque<BytesWrapper> values = new ArrayDeque<>();

	/**
	 * 绝对毫秒过期时间，-1表示永久有效。
	 */
	private volatile long timeout = -1L;

	/**
	 * 按LPUSH语义将多个元素依次插入列表头部。
	 *
	 * @param elements 需要插入的元素
	 * @return 插入完成后的列表长度
	 */
	public synchronized long leftPush(List<BytesWrapper> elements) {
		Objects.requireNonNull(elements, "列表元素不能为空");

		for (BytesWrapper element : elements) {
			values.addFirst(
					Objects.requireNonNull(element, "列表元素不能为空")
			);
		}

		return values.size();
	}

	/**
	 * 从列表头部弹出一个元素。
	 *
	 * @return 头部元素；列表为空时返回null
	 */
	public synchronized BytesWrapper leftPop() {
		return values.pollFirst();
	}

	/**
	 * 按照Redis的LRANGE语义查询指定闭区间内的元素。
	 *
	 * @param start 起始下标，负数表示从列表尾部开始计算
	 * @param stop  结束下标，负数表示从列表尾部开始计算
	 * @return 指定范围内的元素
	 */
	public synchronized List<BytesWrapper> range(long start, long stop) {
		int size = values.size();

		if (size == 0) {
			return List.of();
		}

		// 负数下标从尾部计算，例如-1表示最后一个元素。
		long normalizedStart = start < 0L ? size + start : start;
		long normalizedStop = stop < 0L ? size + stop : stop;

		// 起始位置越过左边界时裁剪到0。
		if (normalizedStart < 0L) {
			normalizedStart = 0L;
		}

		// 结束位置仍小于0、起始位置越过右边界，或者区间颠倒时返回空列表。
		if (normalizedStop < 0L || normalizedStart >= size || normalizedStart > normalizedStop) {
			return List.of();
		}

		// 结束位置越过右边界时裁剪到最后一个元素。
		if (normalizedStop >= size) {
			normalizedStop = size - 1L;
		}

		List<BytesWrapper> result = new ArrayList<>();

		int index = 0;
		for (BytesWrapper value : values) {
			if (index > normalizedStop) {
				break;
			}

			if (index >= normalizedStart) {
				result.add(value);
			}

			index++;
		}

		return result;
	}

	/**
	 * 获取当前列表长度。
	 *
	 * @return 列表元素数量
	 */
	public synchronized long size() {
		return values.size();
	}

	@Override
	public long timeout() {
		return timeout;
	}

	@Override
	public void setTimeout(long timeout) {
		this.timeout = timeout;
	}
}