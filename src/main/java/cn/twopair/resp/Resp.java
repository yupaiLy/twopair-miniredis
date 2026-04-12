package cn.twopair.resp;

import cn.twopair.datatype.BytesWrapper;
import io.netty.buffer.ByteBuf;

/**
 * @author ljj
 * @description RESP协议对象顶层抽象
 * @date 2026/4/10
 * @twopair
 */
public interface Resp {
	//todo 先建 RESP 类型体系，暂不支持中文

	static Resp decode(ByteBuf buffer) {
		if (buffer.readableBytes() <= 0) {
			throw new IllegalStateException("没有可读取的数据");
		}
		char tyte = (char) buffer.readByte();

		if (tyte == RespType.STATUS.getCode()) {
			return new SimpleString(getString(buffer));
		} else if (tyte == RespType.ERROR.getCode()) {
			return new Errors(getString(buffer));
		} else if (tyte == RespType.INTEGER.getCode()) {
			return new RespInt(getNumber(buffer));
		} else if (tyte == RespType.BULK_STRING.getCode()) {
			int length = getNumber(buffer);
			if (length == -1) {
				return BulkString.NIL;
			}
			if (length < 0) {
				throw new IllegalStateException("BulkString长度非法");
			}
			if (buffer.readableBytes() < length + 2) {
				throw new IllegalStateException("没有读取到完整的命令");
			}

			byte[] bytes = new byte[length];
			buffer.readBytes(bytes);

			if (buffer.readByte() != RespType.R.getCode() || buffer.readByte() != RespType.N.getCode()) {
				throw new IllegalStateException("没有读取到完整的命令");
			}
			return new BulkString(new BytesWrapper(bytes));
		} else if (tyte == RespType.ARRAY.getCode()) {
			int length = getNumber(buffer);
			if (length < 0) {
				throw new IllegalStateException("数组长度不能小于0");
			}
			Resp[] array = new Resp[length];
			for (int i = 0; i < length; i++) {
				array[i] = decode(buffer);
			}
			return new RespArray(array);
		} else {
			throw new IllegalStateException("未知RESP类型: " + tyte);
		}
	}

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

	static int getNumber(ByteBuf buffer) {
		if (buffer.readableBytes() <= 0) {
			throw new IllegalStateException("没有可读取的数据");
		}
		int num = 0;
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
