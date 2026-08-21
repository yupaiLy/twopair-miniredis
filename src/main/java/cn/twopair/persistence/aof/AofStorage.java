package cn.twopair.persistence.aof;

import cn.twopair.resp.RespArray;

import java.io.IOException;
import java.util.List;

/**
 * @author ljj
 * @description 定义AOF底层追加、刷盘和关闭能力。
 * @date 2026/8/20
 * @twopair
 */
interface AofStorage extends AutoCloseable {

	/**
	 * 按顺序追加一批RESP命令。
	 *
	 * @param commands 需要追加的命令
	 * @throws IOException 文件写入失败时抛出
	 */
	void appendAll(List<RespArray> commands) throws IOException;

	/**
	 * 将已经写入文件缓存的数据强制刷入磁盘。
	 *
	 * @throws IOException 刷盘失败时抛出
	 */
	void force() throws IOException;

	/**
	 * 关闭底层AOF存储资源。
	 *
	 * @throws IOException 资源关闭失败时抛出
	 */
	@Override
	void close() throws IOException;
}
