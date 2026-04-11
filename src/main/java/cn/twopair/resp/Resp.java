package cn.twopair.resp;

import io.netty.buffer.ByteBuf;

/**
 * @author ljj
 * @description RESP协议对象顶层抽象
 * @date 2026/4/10
 * @twopair
 */
public interface Resp {
	//todo 先建 RESP 类型体系，暂不支持中文
	static String getString(ByteBuf buffer) {
		StringBuilder builder = new StringBuilder();
		byte b;
		while (buffer.readableBytes() > 0) {
			b = buffer.readByte();
			if (b == RespType.R.getCode()) {
				break;
			}
			builder.append((char) b);
		}
		if (buffer.readableBytes() == 0 || buffer.readByte() != RespType.N.getCode()) {
			throw new IllegalStateException("没有读取到完整的命令");
		}
		return builder.toString();
	}

	static long getNumber(ByteBuf buffer) {
		if (buffer.readableBytes() <= 0) {
			throw new IllegalStateException("没有可读取的数据");
		}
		long num = 0;
		byte b;
		boolean positive = true;
		if (buffer.readableBytes() > 0) {
			b = buffer.readByte();
			if (b == RespType.NEGATIVE.getCode()) {
				positive = false;
			} else if (b >= RespType.ZERO.getCode() && b <= RespType.NINE.getCode()) {
				num = b - RespType.ZERO.getCode();
			} else {
				throw new IllegalStateException("数字格式非法");
			}
		}

		while (buffer.readableBytes() > 0) {
			b = buffer.readByte();
			if (b == RespType.R.getCode()) {
				break;
			}
			if (b >= RespType.ZERO.getCode() && b <= RespType.NINE.getCode()) {
				num = num * 10 + (b - RespType.ZERO.getCode());
			} else {
				throw new IllegalStateException("数字格式非法");
			}
		}
		if (buffer.readableBytes() == 0 || buffer.readByte() != RespType.N.getCode()) {
			throw new IllegalStateException("没有读取到完整的命令");
		}
		return positive ? num : -num;
	}
}
