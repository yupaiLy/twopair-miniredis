package cn.twopair.persistence.aof;

import java.util.Locale;

/**
 * @author ljj
 * @description 定义AOF刷盘策略，并负责解析外部配置值。
 * @date 2026/8/20
 * @twopair
 */
public enum AofFsyncPolicy {

	/**
	 * 每批写命令完成后立即执行强制刷盘。
	 */
	ALWAYS,

	/**
	 * 写命令先进入文件缓存，由后台任务每秒执行一次刷盘。
	 */
	EVERYSEC;

	/**
	 * 将外部配置值解析为AOF刷盘策略。
	 *
	 * @param value 策略配置值
	 * @return 对应的AOF刷盘策略
	 * @throws IllegalArgumentException 配置为空或不受支持时抛出
	 */
	public static AofFsyncPolicy parse(String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("AOF刷盘策略不能为空");
		}

		String normalized = value.trim().toUpperCase(Locale.ROOT);

		try {
			return AofFsyncPolicy.valueOf(normalized);
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("不支持的AOF刷盘策略: " + value, exception);
		}
	}
}
