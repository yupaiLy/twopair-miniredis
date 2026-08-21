package cn.twopair.core.impl;

import cn.twopair.core.RedisCore;
import cn.twopair.core.WrongTypeException;
import cn.twopair.datatype.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Redis核心实现类
 *
 * @author ljj
 */
public class RedisCoreImpl implements RedisCore {
	private static final Logger LOGGER = LoggerFactory.getLogger(RedisCoreImpl.class);
	private final ConcurrentHashMap<BytesWrapper, RedisData> map = new ConcurrentHashMap<>();
	/**
	 * 提供当前毫秒时间。
	 *
	 * <p>生产环境使用{@link System#currentTimeMillis() 当前毫秒时间方法}，
	 * 测试环境可以注入可控时间。
	 */
	private final LongSupplier currentTimeMillis;

	/**
	 * 创建使用系统时间的 {@link RedisCore Redis核心存储}实现。
	 */
	public RedisCoreImpl() {
		this(System::currentTimeMillis);
	}

	/**
	 * 创建使用指定时间源的 {@link RedisCore Redis核心存储}实现。
	 *
	 * @param currentTimeMillis 提供当前毫秒时间的时间源，不能为 {@code null}
	 */
	public RedisCoreImpl(LongSupplier currentTimeMillis) {
		this.currentTimeMillis = Objects.requireNonNull(
				currentTimeMillis,
				"时间源不能为空"
		);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void put(BytesWrapper key, RedisData value) {
		map.put(key, value);
	}

	/**
	 * 获取数据同时检测数据是否过期（惰性删除）。
	 *
	 * @param key 键
	 * @return 对应的数据；key不存在或已过期时返回 {@code null}
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
				LOGGER.debug("惰性过期删除完成");
				return null;
			}

			// 数据已被其他线程替换，重新读取并判断新值。
		}

	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean exist(BytesWrapper key) {
		// 直接调用获取方法
		return get(key) != null;
	}

	/**
	 * 删除一个或多个key，已过期的数据在逻辑上等同于不存在。
	 *
	 * @param keys 需要删除的key
	 * @return 实际删除的key数量
	 */
	@Override
	public long delete(List<BytesWrapper> keys) {
		Objects.requireNonNull(keys, "删除的key不能为空");

		if (keys.isEmpty()) {
			throw new IllegalArgumentException("删除的key不能为空");
		}

		long deletedCount = 0L;

		for (BytesWrapper key : keys) {
			Objects.requireNonNull(key, "删除的key不能为空");

			/*
			 * 沿用expire立即删除的惯用法：
			 * get内部已经完成过期检测和惰性删除，
			 * 条件删除只移除刚才读取到的对象，防止误删并发写入的新值。
			 */
			while (true) {
				RedisData redisData = get(key);

				// key不存在或已过期，逻辑上等同于没有删除任何数据。
				if (redisData == null) {
					break;
				}

				if (map.remove(key, redisData)) {
					deletedCount++;
					break;
				}

				// 数据已被其他线程替换，重新读取后再删除。
			}
		}

		return deletedCount;
	}

	/**
	 * 为存在的 key 设置过期时间，非正数表示立即删除。
	 *
	 * @param key     需要设置过期时间的key
	 * @param seconds 过期秒数
	 * @return key存在并成功处理时返回 {@code true}
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

	/**
	 * 为指定key设置绝对毫秒过期时间，过去的时间表示立即删除。
	 *
	 * @param key 需要设置过期时间的key
	 * @param expireAtMillis 绝对毫秒时间戳
	 * @return key存在并成功处理时返回 {@code true}
	 */
	@Override
	public boolean expireAt(BytesWrapper key, long expireAtMillis) {
		AtomicBoolean success = new AtomicBoolean(false);

		map.computeIfPresent(key, (currentKey, redisData) -> {
			long now = currentTimeMillis.getAsLong();
			long oldTimeout = redisData.timeout();

			// 已经过期的数据在逻辑上等同于不存在。
			if (oldTimeout != -1L && oldTimeout <= now) {
				return null;
			}

			success.set(true);

			// 目标时间已经到达，直接删除当前key。
			if (expireAtMillis <= now) {
				return null;
			}

			redisData.setTimeout(expireAtMillis);
			return redisData;
		});

		return success.get();
	}

	/**
	 * {@inheritDoc}
	 */
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
	 * {@inheritDoc}
	 */
	@Override
	public long leftPush(BytesWrapper key, List<BytesWrapper> elements) {
		Objects.requireNonNull(key, "列表key不能为空");
		Objects.requireNonNull(elements, "列表元素不能为空");

		if (elements.isEmpty()) {
			throw new IllegalArgumentException("列表元素不能为空");
		}

		AtomicLong resultLength = new AtomicLong();

		map.compute(key, (currentKey, currentValue) -> {
			long now = currentTimeMillis.getAsLong();

			// 已过期的数据在逻辑上等同于不存在。
			if (currentValue != null
					&& currentValue.timeout() != -1L
					&& currentValue.timeout() <= now) {
				currentValue = null;
			}

			RedisList redisList;

			if (currentValue == null) {
				redisList = new RedisList();
			} else if (currentValue instanceof RedisList list) {
				redisList = list;
			} else {
				throw new WrongTypeException();
			}

			resultLength.set(redisList.leftPush(elements));
			return redisList;
		});

		return resultLength.get();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public long rightPush(BytesWrapper key, List<BytesWrapper> elements) {
		Objects.requireNonNull(key, "列表key不能为空");
		Objects.requireNonNull(elements, "列表元素不能为空");

		if (elements.isEmpty()) {
			throw new IllegalArgumentException("列表元素不能为空");
		}

		AtomicLong resultLength = new AtomicLong();

		map.compute(key, (currentKey, currentValue) -> {
			long now = currentTimeMillis.getAsLong();

			// 已过期的数据在逻辑上等同于不存在。
			if (currentValue != null && currentValue.timeout() != -1L && currentValue.timeout() <= now) {
				currentValue = null;
			}

			RedisList redisList;

			if (currentValue == null) {
				redisList = new RedisList();
			} else if (currentValue instanceof RedisList list) {
				redisList = list;
			} else {
				throw new WrongTypeException();
			}

			resultLength.set(redisList.rightPush(elements));
			return redisList;
		});

		return resultLength.get();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public BytesWrapper leftPop(BytesWrapper key) {
		Objects.requireNonNull(key, "列表key不能为空");
		AtomicReference<BytesWrapper> poppedElement = new AtomicReference<>();

		map.computeIfPresent(key, (currentKey, currentValue) -> {
			long now = currentTimeMillis.getAsLong();

			// 已经过期的数据直接删除，并按照不存在处理。
			if (currentValue.timeout() != -1L && currentValue.timeout() <= now) {
				return null;
			}

			if (!(currentValue instanceof RedisList redisList)) {
				throw new WrongTypeException();
			}

			BytesWrapper element = redisList.leftPop();
			poppedElement.set(element);

			// Redis不会保留空列表，最后一个元素弹出后删除整个key。
			if (redisList.size() == 0L) {
				return null;
			}

			return redisList;
		});

		return poppedElement.get();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public BytesWrapper rightPop(BytesWrapper key) {
		Objects.requireNonNull(key, "列表key不能为空");
		AtomicReference<BytesWrapper> poppedElement = new AtomicReference<>();

		map.computeIfPresent(key, (currentKey, currentValue) -> {
			long now = currentTimeMillis.getAsLong();

			// 已过期的数据直接删除，并按照不存在处理。
			if (currentValue.timeout() != -1L && currentValue.timeout() <= now) {
				return null;
			}

			if (!(currentValue instanceof RedisList redisList)) {
				throw new WrongTypeException();
			}

			BytesWrapper element = redisList.rightPop();
			poppedElement.set(element);

			// Redis不会保留空列表，最后一个元素弹出后删除整个key。
			if (redisList.size() == 0L) {
				return null;
			}

			return redisList;
		});

		return poppedElement.get();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public long listLength(BytesWrapper key) {
		Objects.requireNonNull(key, "列表key不能为空");
		AtomicLong resultLength = new AtomicLong();

		map.computeIfPresent(key, (currentKey, currentValue) -> {
			long now = currentTimeMillis.getAsLong();

			// 已过期的数据直接删除，并按照不存在处理。
			if (currentValue.timeout() != -1L && currentValue.timeout() <= now) {
				return null;
			}

			if (!(currentValue instanceof RedisList redisList)) {
				throw new WrongTypeException();
			}

			resultLength.set(redisList.size());
			return redisList;
		});

		return resultLength.get();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public List<BytesWrapper> listRange(BytesWrapper key, long start, long stop) {
		Objects.requireNonNull(key, "列表key不能为空");
		AtomicReference<List<BytesWrapper>> result = new AtomicReference<>(List.of());

		map.computeIfPresent(key, (currentKey, currentValue) -> {
			long now = currentTimeMillis.getAsLong();

			// 已过期的数据直接删除，并按照不存在处理。
			if (currentValue.timeout() != -1L && currentValue.timeout() <= now) {
				return null;
			}

			if (!(currentValue instanceof RedisList redisList)) {
				throw new WrongTypeException();
			}

			result.set(redisList.range(start, stop));
			return redisList;
		});

		return result.get();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public long putHashFields(BytesWrapper key, Map<BytesWrapper, BytesWrapper> fields) {
		Objects.requireNonNull(key, "Hash的key不能为空");
		Objects.requireNonNull(fields, "Hash字段不能为空");

		if (fields.isEmpty()) {
			throw new IllegalArgumentException("Hash字段不能为空");
		}

		AtomicLong addedCount = new AtomicLong();

		map.compute(key, (currentKey, currentValue) -> {
			long now = currentTimeMillis.getAsLong();

			// 已过期的数据在逻辑上等同于不存在。
			if (currentValue != null
					&& currentValue.timeout() != -1L
					&& currentValue.timeout() <= now) {
				currentValue = null;
			}

			RedisHash redisHash;

			if (currentValue == null) {
				redisHash = new RedisHash();
			} else if (currentValue instanceof RedisHash hash) {
				redisHash = hash;
			} else {
				throw new WrongTypeException();
			}

			addedCount.set(redisHash.set(fields));
			return redisHash;
		});

		return addedCount.get();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public BytesWrapper getHashField(BytesWrapper key, BytesWrapper field) {
		Objects.requireNonNull(key, "Hash的key不能为空");
		Objects.requireNonNull(field, "Hash的field不能为空");
		AtomicReference<BytesWrapper> result = new AtomicReference<>();

		map.computeIfPresent(key, (currentKey, currentValue) -> {
			long now = currentTimeMillis.getAsLong();

			// 已过期的数据直接删除，并按照不存在处理。
			if (currentValue.timeout() != -1L && currentValue.timeout() <= now) {
				return null;
			}

			if (!(currentValue instanceof RedisHash redisHash)) {
				throw new WrongTypeException();
			}

			result.set(redisHash.get(field));
			return redisHash;
		});

		return result.get();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public long deleteHashFields(BytesWrapper key, List<BytesWrapper> fields) {
		Objects.requireNonNull(key, "Hash的key不能为空");
		Objects.requireNonNull(fields, "Hash字段不能为空");

		if (fields.isEmpty()) {
			throw new IllegalArgumentException("Hash字段不能为空");
		}

		AtomicLong deletedCount = new AtomicLong();

		map.computeIfPresent(key, (currentKey, currentValue) -> {
			long now = currentTimeMillis.getAsLong();

			// 已过期的数据直接删除，并按照不存在处理。
			if (currentValue.timeout() != -1L && currentValue.timeout() <= now) {
				return null;
			}

			if (!(currentValue instanceof RedisHash redisHash)) {
				throw new WrongTypeException();
			}

			deletedCount.set(redisHash.delete(fields));

			// Redis不保留空Hash，最后一个field删除后移除整个key。
			if (redisHash.size() == 0L) {
				return null;
			}

			return redisHash;
		});

		return deletedCount.get();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public long getHashSize(BytesWrapper key) {
		Objects.requireNonNull(key, "Hash的key不能为空");
		AtomicLong resultLength = new AtomicLong();

		map.computeIfPresent(key, (currentKey, currentValue) -> {
			long now = currentTimeMillis.getAsLong();

			// 已过期的数据直接删除，并按照不存在处理。
			if (currentValue.timeout() != -1L && currentValue.timeout() <= now) {
				return null;
			}

			if (!(currentValue instanceof RedisHash redisHash)) {
				throw new WrongTypeException();
			}

			resultLength.set(redisHash.size());
			return redisHash;
		});

		return resultLength.get();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public List<Map.Entry<BytesWrapper, BytesWrapper>> scanHashEntries(BytesWrapper key) {
		Objects.requireNonNull(key, "Hash的key不能为空");
		AtomicReference<List<Map.Entry<BytesWrapper, BytesWrapper>>> result = new AtomicReference<>(List.of());

		map.computeIfPresent(key, (currentKey, currentValue) -> {
			long now = currentTimeMillis.getAsLong();

			if (currentValue.timeout() != -1L && currentValue.timeout() <= now) {
				return null;
			}

			if (!(currentValue instanceof RedisHash redisHash)) {
				throw new WrongTypeException();
			}

			result.set(redisHash.entriesSnapshot());
			return redisHash;
		});

		return result.get();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public long addSetMembers(BytesWrapper key, List<BytesWrapper> members) {
		Objects.requireNonNull(key, "Set的key不能为空");
		Objects.requireNonNull(members, "Set成员不能为空");

		if (members.isEmpty()) {
			throw new IllegalArgumentException("Set成员不能为空");
		}

		AtomicLong addedCount = new AtomicLong();

		map.compute(key, (currentKey, currentValue) -> {
			long now = currentTimeMillis.getAsLong();

			// 已过期的数据在逻辑上等同于不存在。
			if (currentValue != null
					&& currentValue.timeout() != -1L
					&& currentValue.timeout() <= now) {
				currentValue = null;
			}

			RedisSet redisSet;

			if (currentValue == null) {
				redisSet = new RedisSet();
			} else if (currentValue instanceof RedisSet set) {
				redisSet = set;
			} else {
				throw new WrongTypeException();
			}

			addedCount.set(redisSet.add(members));
			return redisSet;
		});

		return addedCount.get();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public long removeSetMembers(BytesWrapper key, List<BytesWrapper> members) {
		Objects.requireNonNull(key, "Set的key不能为空");
		Objects.requireNonNull(members, "Set成员不能为空");

		if (members.isEmpty()) {
			throw new IllegalArgumentException("Set成员不能为空");
		}

		AtomicLong removedCount = new AtomicLong();

		map.computeIfPresent(key, (currentKey, currentValue) -> {
			long now = currentTimeMillis.getAsLong();

			// 已过期的数据直接删除，并按照不存在处理。
			if (currentValue.timeout() != -1L && currentValue.timeout() <= now) {
				return null;
			}

			if (!(currentValue instanceof RedisSet redisSet)) {
				throw new WrongTypeException();
			}

			removedCount.set(redisSet.remove(members));

			// Redis不保留空Set，最后一个成员删除后移除整个key。
			if (redisSet.size() == 0L) {
				return null;
			}

			return redisSet;
		});

		return removedCount.get();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean containsSetMember(BytesWrapper key, BytesWrapper member) {
		Objects.requireNonNull(key, "Set的key不能为空");
		Objects.requireNonNull(member, "Set的member不能为空");
		AtomicBoolean result = new AtomicBoolean(false);

		map.computeIfPresent(key, (currentKey, currentValue) -> {
			long now = currentTimeMillis.getAsLong();

			// 已过期的数据直接删除，并按照不存在处理。
			if (currentValue.timeout() != -1L && currentValue.timeout() <= now) {
				return null;
			}

			if (!(currentValue instanceof RedisSet redisSet)) {
				throw new WrongTypeException();
			}

			result.set(redisSet.contains(member));
			return redisSet;
		});

		return result.get();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public long getSetSize(BytesWrapper key) {
		Objects.requireNonNull(key, "Set的key不能为空");
		AtomicLong resultSize = new AtomicLong();

		map.computeIfPresent(key, (currentKey, currentValue) -> {
			long now = currentTimeMillis.getAsLong();

			// 已过期的数据直接删除，并按照不存在处理。
			if (currentValue.timeout() != -1L && currentValue.timeout() <= now) {
				return null;
			}

			if (!(currentValue instanceof RedisSet redisSet)) {
				throw new WrongTypeException();
			}

			resultSize.set(redisSet.size());
			return redisSet;
		});

		return resultSize.get();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public List<BytesWrapper> scanSetMembers(BytesWrapper key) {
		Objects.requireNonNull(key, "Set的key不能为空");
		AtomicReference<List<BytesWrapper>> result = new AtomicReference<>(List.of());

		map.computeIfPresent(key, (currentKey, currentValue) -> {
			long now = currentTimeMillis.getAsLong();

			if (currentValue.timeout() != -1L && currentValue.timeout() <= now) {
				return null;
			}

			if (!(currentValue instanceof RedisSet redisSet)) {
				throw new WrongTypeException();
			}

			result.set(redisSet.membersSnapshot());
			return redisSet;
		});

		return result.get();
	}

	/**
	 * 获取当前所有未过期key的弱一致性快照。
	 *
	 * <p>{@link ConcurrentHashMap 并发哈希表}的遍历不会阻塞并发读写，因此扫描期间新增或删除的key
	 * 不保证一定出现在本次结果中，这与Redis SCAN的弱一致性语义相符。</p>
	 *
	 * @return 按字节顺序排列的有效key列表
	 */
	@Override
	public List<BytesWrapper> scanKeys() {
		long now = currentTimeMillis.getAsLong();
		List<BytesWrapper> keys = new ArrayList<>();

		for (Map.Entry<BytesWrapper, RedisData> entry : map.entrySet()) {
			RedisData redisData = entry.getValue();
			long timeout = redisData.timeout();

			if (timeout != -1L && timeout <= now) {
				// 条件删除可以防止并发写入的新值被当前扫描误删。
				map.remove(entry.getKey(), redisData);
				continue;
			}

			keys.add(entry.getKey());
		}

		// 固定顺序便于游标分页，也让测试和GUI展示结果保持稳定。
		keys.sort((left, right) -> Arrays.compareUnsigned(left.getByteArray(), right.getByteArray()));
		return keys;
	}

	/**
	 * 写入键值对并同时设置以秒为单位的过期时间。
	 *
	 * <p>秒数会基于当前时间源换算成绝对毫秒时间戳后再保存。
	 *
	 * @param key     需要写入的键，不能为 {@code null}
	 * @param value   需要写入的值，不能为 {@code null}
	 * @param seconds 过期秒数，必须大于0
	 * @throws IllegalArgumentException 当 {@code seconds} 小于等于0时抛出
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
