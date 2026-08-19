package cn.twopair.datatype;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * @author ljj
 * @description Redis集合值对象，封装唯一成员集合和过期时间。
 * @date 2026/8/19
 * @twopair
 */
public class RedisSet implements RedisData {

	/**
	 * 使用HashSet保证成员唯一，并支持平均O(1)的增删查。
	 */
	private final Set<BytesWrapper> values = new HashSet<>();

	/**
	 * 绝对毫秒过期时间，-1表示永久有效。
	 */
	private volatile long timeout = -1L;

	/**
	 * 添加一个或多个成员。
	 *
	 * @param members 需要添加的成员
	 * @return 本次新增的成员数量，已存在的成员不计数
	 */
	public synchronized long add(List<BytesWrapper> members) {
		Objects.requireNonNull(members, "Set成员不能为空");

		// 先验证全部成员，避免添加一部分后才遇到非法数据。
		for (BytesWrapper member : members) {
			Objects.requireNonNull(member, "Set的member不能为空");
		}

		long addedCount = 0L;

		for (BytesWrapper member : members) {
			if (values.add(member)) {
				addedCount++;
			}
		}

		return addedCount;
	}

	/**
	 * 删除一个或多个成员。
	 *
	 * @param members 需要删除的成员
	 * @return 实际删除的成员数量
	 */
	public synchronized long remove(List<BytesWrapper> members) {
		Objects.requireNonNull(members, "Set成员不能为空");

		// 提前验证全部成员，保证异常发生前不会修改数据。
		for (BytesWrapper member : members) {
			Objects.requireNonNull(member, "Set的member不能为空");
		}

		long removedCount = 0L;

		for (BytesWrapper member : members) {
			if (values.remove(member)) {
				removedCount++;
			}
		}

		return removedCount;
	}

	/**
	 * 判断指定成员是否存在。
	 *
	 * @param member 需要判断的成员
	 * @return 成员存在时返回true
	 */
	public synchronized boolean contains(BytesWrapper member) {
		Objects.requireNonNull(member, "Set的member不能为空");
		return values.contains(member);
	}

	/**
	 * 获取Set当前包含的成员数量。
	 *
	 * @return 成员数量
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