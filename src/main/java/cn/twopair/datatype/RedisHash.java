package cn.twopair.datatype;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * @author ljj
 * @description Redis哈希值对象，封装字段映射和过期时间。
 * @date 2026/8/18
 * @twopair
 */
public class RedisHash implements RedisData {

	/**
	 * 保存Hash内部的field和value。
	 */
	private final Map<BytesWrapper, BytesWrapper> values = new HashMap<>();

	/**
	 * 绝对毫秒过期时间，-1表示永久有效。
	 */
	private volatile long timeout = -1L;

	/**
	 * 批量新增或覆盖Hash字段。
	 *
	 * @param fields 需要写入的字段和值
	 * @return 本次新增的字段数量，覆盖已有字段不计数
	 */
	public synchronized long set(Map<BytesWrapper, BytesWrapper> fields) {
		Objects.requireNonNull(fields, "Hash字段不能为空");

		// 先验证所有参数，避免写入一部分后才遇到非法数据。
		for (Map.Entry<BytesWrapper, BytesWrapper> entry : fields.entrySet()) {
			Objects.requireNonNull(entry.getKey(), "Hash的field不能为空");
			Objects.requireNonNull(entry.getValue(), "Hash的value不能为空");
		}

		long addedCount = 0L;

		for (Map.Entry<BytesWrapper, BytesWrapper> entry : fields.entrySet()) {
			if (!values.containsKey(entry.getKey())) {
				addedCount++;
			}

			values.put(entry.getKey(), entry.getValue());
		}

		return addedCount;
	}

	/**
	 * 获取指定字段的值。
	 *
	 * @param field 字段
	 * @return 字段值；字段不存在时返回null
	 */
	public synchronized BytesWrapper get(BytesWrapper field) {
		Objects.requireNonNull(field, "Hash的field不能为空");
		return values.get(field);
	}

	/**
	 * 删除一个或多个字段。
	 *
	 * @param fields 需要删除的字段
	 * @return 实际删除的字段数量
	 */
	public synchronized long delete(List<BytesWrapper> fields) {
		Objects.requireNonNull(fields, "Hash字段不能为空");

		// 提前验证全部字段，保证异常发生前不会修改数据。
		for (BytesWrapper field : fields) {
			Objects.requireNonNull(field, "Hash的field不能为空");
		}

		long deletedCount = 0L;

		for (BytesWrapper field : fields) {
			if (values.remove(field) != null) {
				deletedCount++;
			}
		}

		return deletedCount;
	}

	/**
	 * 获取Hash当前包含的字段数量。
	 *
	 * @return 字段数量
	 */
	public synchronized long size() {
		return values.size();
	}

	/**
	 * 获取Hash字段和值的有序快照。
	 *
	 * @return 按field字节顺序排列的不可变键值项列表
	 */
	public synchronized List<Map.Entry<BytesWrapper, BytesWrapper>> entriesSnapshot() {
		List<Map.Entry<BytesWrapper, BytesWrapper>> entries = new ArrayList<>(values.size());

		for (Map.Entry<BytesWrapper, BytesWrapper> entry : values.entrySet()) {
			entries.add(Map.entry(entry.getKey(), entry.getValue()));
		}

		entries.sort(Map.Entry.comparingByKey());
		return entries;
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
