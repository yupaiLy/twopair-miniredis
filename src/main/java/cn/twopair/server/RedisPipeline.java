package cn.twopair.server;

import cn.twopair.core.RedisCore;
import cn.twopair.persistence.aof.AofPersistence;
import cn.twopair.server.codec.RespDecoder;
import cn.twopair.server.codec.RespEncoder;
import cn.twopair.server.handler.CommandHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.flush.FlushConsolidationHandler;

import java.util.Objects;

/**
 * 统一组装MiniRedis连接的RESP编解码、响应刷出优化和命令处理链路。
 *
 * @author ljj
 */
public final class RedisPipeline {

	/**
	 * 工具类不允许创建实例。
	 */
	private RedisPipeline() {
	}

	/**
	 * 为客户端连接安装完整的MiniRedis Pipeline。
	 *
	 * <p>{@code FlushConsolidationHandler} 会合并同一读取批次中的flush事件，
	 * 默认累计到256次或触发 {@code channelReadComplete} 时统一向底层刷出。
	 *
	 * @param pipeline       当前客户端连接的Pipeline
	 * @param redisCore      所有连接共享的Redis核心存储
	 * @param aofPersistence 所有连接共享的AOF协调器；为 {@code null} 表示禁用AOF
	 * @throws NullPointerException pipeline或redisCore为 {@code null} 时抛出
	 */
	public static void configure(ChannelPipeline pipeline, RedisCore redisCore, AofPersistence aofPersistence) {
		Objects.requireNonNull(pipeline, "ChannelPipeline不能为空");
		Objects.requireNonNull(redisCore, "RedisCore不能为空");
		pipeline.addLast(new RespDecoder()).addLast(new RespEncoder()).addLast(new FlushConsolidationHandler()).addLast(new CommandHandler(redisCore, aofPersistence));
	}
}
