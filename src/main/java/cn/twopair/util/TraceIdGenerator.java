package cn.twopair.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * @author ljj
 * @description 为每条Redis命令生成进程内唯一且便于阅读的追踪标识。
 * @date 2026/8/19
 * @twopair
 */
public final class TraceIdGenerator {

	private static final AtomicLong SEQUENCE = new AtomicLong();

	private TraceIdGenerator() {
	}

	/**
	 * 生成下一条命令的追踪标识。
	 *
	 * @return 由当前时间和递增序列组成的traceId
	 */
	public static String next() {
		long timestamp = System.currentTimeMillis();
		long sequence = SEQUENCE.incrementAndGet();
		return Long.toUnsignedString(timestamp, 36) + "-" + Long.toUnsignedString(sequence, 36);
	}
}
