package cn.twopair.core.impl;

import cn.twopair.core.RedisCore;
import cn.twopair.datatype.BytesWrapper;
import cn.twopair.datatype.RedisData;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * @author ljj
 * @description Redis核心实现类
 * @date 2026/4/9
 * @twopair
 */
public class RedisCoreImpl implements RedisCore {
	private final ConcurrentHashMap<BytesWrapper, RedisData> map = new ConcurrentHashMap<>();
	/**
	 * 提供当前毫秒时间。
	 *
	 * <p>生产环境使用 System.currentTimeMillis，
	 * 测试环境可以注入可控时间。
	 */
	private final LongSupplier currentTimeMillis;

	/**
	 * @author ljj
	 * @description 创建使用系统时间的 RedisCore。
	 * @date 2026/7/16
	 * @twopair
	 */
	public RedisCoreImpl() {
		this(System::currentTimeMillis);
	}

	/**
	 * @author ljj
	 * @description 创建使用指定时间源的 RedisCore。
	 * @date 2026/7/16
	 * @twopair
	 */
	public RedisCoreImpl(LongSupplier currentTimeMillis) {
		this.currentTimeMillis = Objects.requireNonNull(
				currentTimeMillis,
				"时间源不能为空"
		);
	}

	@Override
	public void put(BytesWrapper key, RedisData value) {
		map.put(key, value);
	}

	/**
	 * 获取数据同时检测数据是否过期（惰性删除）
	 *
	 * @param key 键
	 * @return 值
	 */
	@Override
	public RedisData get(BytesWrapper key) {
		while (true) {
			RedisData redisData = map.get(key);
			if (redisData == null) {
				return null;
			}

			long timeout = redisData.timeout();
			// 从注入的时间源获取时间。
			long now = currentTimeMillis.getAsLong();

			if (timeout == -1 || timeout > now) {
				return redisData;
			}

			/*
			 * 只有 Map 中仍然是刚才读取的对象时才删除。
			 * 防止其他 Worker 刚写入新值，却被当前线程误删。
			 */
			if (map.remove(key, redisData)) {
				return null;
			}

			// 数据已被其他线程替换，重新读取并判断新值。
		}

	}

	@Override
	public boolean exist(BytesWrapper key) {
		// 直接调用获取方法
		return get(key) != null;
	}

	/**
	 * @author ljj
	 * @description 为存在的 key 设置过期时间，非正数表示立即删除。
	 * @date 2026/7/16
	 * @twopair
	 */
	@Override
	public boolean expire(BytesWrapper key, long seconds) {
		if (seconds <= 0L) {
			while (true) {
				RedisData redisData = get(key);
				if (redisData == null) {
					return false;
				}

				// 只删除刚才读取到的对象，防止误删并发写入的新值。
				if (map.remove(key, redisData)) {
					return true;
				}
			}
		}

		/*
		 * computeIfPresent 会针对当前 key 原子执行更新。
		 * key 不存在时不会执行 Lambda，并直接返回 null。
		 */
		RedisData result = map.computeIfPresent(key, (currentKey, redisData) -> {
			long now = currentTimeMillis.getAsLong();
			long oldTimeout = redisData.timeout();

			// 已经过期的数据在这里直接删除。
			if (oldTimeout != -1L && oldTimeout <= now) {
				return null;
			}

			try {
				long timeoutMillis = Math.multiplyExact(seconds, 1000L);
				long expireAt = Math.addExact(now, timeoutMillis);

				// RedisData 保存的是绝对过期时间，而不是剩余秒数。
				redisData.setTimeout(expireAt);
				return redisData;
			} catch (ArithmeticException e) {
				throw new IllegalArgumentException("过期时间超出long范围", e);
			}
		});

		return result != null;
	}

	@Override
	public long ttl(BytesWrapper key) {
		while (true) {
			RedisData redisData = get(key);
			if (redisData == null) {
				return -2L;
			}
			long timeout = redisData.timeout();

			if (timeout == -1) {
				// 确认查询期间没有被其他线程替换。
				if (map.get(key) == redisData) {
					return -1L;
				}
				continue;
			}
			long now = currentTimeMillis.getAsLong();

			/*
			 * get 之后时间可能继续前进。
			 * 如果此时到达过期时间，需要进行条件删除。
			 */
			if (timeout <= now) {
				if (map.remove(key, redisData)) {
					return -2L;
				}
				continue;
			}

			long remainingSeconds = (timeout - now) / 1000L;

			// 映射没有被替换时，才返回当前对象的 TTL。
			if (map.get(key) == redisData) {
				return remainingSeconds;
			}
		}
	}

	/**
	 * Adds the specified key-value pair to the data store while setting an expiration time for the value.
	 * The expiration time is defined in seconds and is translated to an absolute timestamp using the system's
	 * or the specified time source.
	 *
	 * @param key     the key associated with the value being stored; must not be null
	 * @param value   the value being stored; must not be null
	 * @param seconds the expiration time in seconds; must be greater than zero
	 * @throws IllegalArgumentException if the expiration time is less than or equal to zero
	 */
	@Override
	public void putWithExpiration(BytesWrapper key, RedisData value, long seconds) {
		if (seconds <= 0L) {
			throw new IllegalArgumentException("过期时间必须大于0");
		}
		long now = currentTimeMillis.getAsLong();

		// 使用精确运算，防止超大秒数发生静默溢出。
		long timeoutMillis = Math.multiplyExact(seconds, 1000L);
		long expireAt = Math.addExact(now, timeoutMillis);

		/*
		 * 必须先设置timeout，再放入Map。
		 * 其他线程只会看到旧对象，或者看到已经完整初始化的新对象。
		 */
		value.setTimeout(expireAt);
		map.put(key, value);
	}

	/**
	 * 扫描当前存储并删除已经到达过期时间的数据。
	 *
	 * @return 本次成功删除的数据数量
	 */
	@Override
	public int removeExpired() {
		long now = currentTimeMillis.getAsLong();
		int removedCount = 0;

		// ConcurrentHashMap允许在并发读写期间进行弱一致性遍历。
		for (Map.Entry<BytesWrapper, RedisData> entry : map.entrySet()) {
			RedisData redisData = entry.getValue();
			long timeout = redisData.timeout();

			// -1表示永久存在；未来才过期的数据也不能删除。
			if (timeout == -1L || timeout > now) {
				continue;
			}

			/*
			 * 同时匹配key和旧value。
			 * 如果其他线程刚写入新值，则删除失败，避免误删新数据。
			 */
			if (map.remove(entry.getKey(), redisData)) {
				removedCount++;
			}
		}

		return removedCount;
	}
}
