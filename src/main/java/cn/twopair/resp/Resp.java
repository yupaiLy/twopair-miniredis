package cn.twopair.resp;

import cn.twopair.datatype.BytesWrapper;
import io.netty.buffer.ByteBuf;

import static cn.twopair.datatype.BytesWrapper.CHARSET;

/**
 * RESP协议对象顶层抽象
 *
 * @author ljj
 */
public interface Resp {

	/**
	 * 将 RESP 对象编码为字节并写入指定缓冲区。
	 *
	 * <p>支持 {@link SimpleString}、{@link Errors}、{@link RespInt}、
	 * {@link BulkString} 和 {@link RespArray} 五种类型，
	 * 按RESP协议规则序列化为对应的字节表示。
	 *
	 * @param resp   需要编码的RESP对象
	 * @param buffer 接收编码结果的 {@code ByteBuf} 缓冲区
	 * @throws IllegalStateException 当RESP对象类型不受支持时抛出
	 */
	static void encode(Resp resp, ByteBuf buffer) {
		if (resp instanceof SimpleString) {
			buffer.writeByte(RespType.STATUS.getCode());
			String content = ((SimpleString) resp).getContent();
			buffer.writeBytes(content.getBytes(CHARSET));
			buffer.writeByte(RespType.R.getCode());
			buffer.writeByte(RespType.N.getCode());
		} else if (resp instanceof Errors) {
			buffer.writeByte(RespType.ERROR.getCode());
			String content = ((Errors) resp).getContent();
			buffer.writeBytes(content.getBytes(CHARSET));
			buffer.writeByte(RespType.R.getCode());
			buffer.writeByte(RespType.N.getCode());
		} else if (resp instanceof RespInt) {
			buffer.writeByte(RespType.INTEGER.getCode());
			long value = ((RespInt) resp).getValue();
			buffer.writeBytes(String.valueOf(value).getBytes(CHARSET));
			buffer.writeByte(RespType.R.getCode());
			buffer.writeByte(RespType.N.getCode());
		} else if (resp instanceof BulkString) {
			buffer.writeByte(RespType.BULK_STRING.getCode());
			BytesWrapper bytesWrapper = ((BulkString) resp).getBytesWrapper();
			if (bytesWrapper == null) {
				buffer.writeBytes("-1".getBytes(CHARSET));
				buffer.writeByte(RespType.R.getCode());
				buffer.writeByte(RespType.N.getCode());
			} else if (bytesWrapper.getByteArray().length == 0) {
				buffer.writeByte(RespType.ZERO.getCode());
				buffer.writeByte(RespType.R.getCode());
				buffer.writeByte(RespType.N.getCode());
				buffer.writeByte(RespType.R.getCode());
				buffer.writeByte(RespType.N.getCode());
			} else {
				buffer.writeBytes(String.valueOf(bytesWrapper.getByteArray().length).getBytes(CHARSET));
				buffer.writeByte(RespType.R.getCode());
				buffer.writeByte(RespType.N.getCode());
				buffer.writeBytes(bytesWrapper.getByteArray());
				buffer.writeByte(RespType.R.getCode());
				buffer.writeByte(RespType.N.getCode());
			}
		} else if (resp instanceof RespArray) {
			buffer.writeByte(RespType.ARRAY.getCode());
			Resp[] array = ((RespArray) resp).getArray();
			if (array == null) {
				buffer.writeBytes("-1".getBytes(CHARSET));
				buffer.writeByte(RespType.R.getCode());
				buffer.writeByte(RespType.N.getCode());
			} else {
				buffer.writeBytes(String.valueOf(array.length).getBytes(CHARSET));
				buffer.writeByte(RespType.R.getCode());
				buffer.writeByte(RespType.N.getCode());
				for (Resp r : array) {
					encode(r, buffer);
				}
			}
		} else {
			throw new IllegalStateException("未知RESP类型: " + resp.getClass().getName());
		}
	}

	/**
	 * 尝试从给定缓冲区中解码 RESP（Redis Serialization Protocol）消息。
	 * 如果缓冲区中的数据不足以组成一条完整的 RESP 消息，则将缓冲区的读索引重置到原始位置，
	 * 并返回 {@code null}。
	 *
	 * @param buffer 包含待解码 RESP 数据的缓冲区，不能为 {@code null}
	 * @return 如果存在完整消息，则返回解码后的 {@code Resp} 对象；如果消息不完整，则返回 {@code null}
	 */
	static Resp tryDecode(ByteBuf buffer) {
		// 记录当前读取位置，半包时必须回到这里重新解析。
		buffer.markReaderIndex();

		try {
			Resp resp = decode(buffer);
			return resp;
		} catch (RespIncompleteException e) {
			// 只回滚“数据未收全”的情况；
			buffer.resetReaderIndex();
			return null;
		}
	}


	/**
	 * 从缓冲区解码一条RESP消息。
	 *
	 * @param buffer 包含待解码数据的缓冲区
	 * @return 解码得到的RESP对象
	 * @throws RespIncompleteException 当缓冲区数据不足以组成完整消息时抛出
	 * @throws IllegalStateException    当数据不符合RESP协议时抛出
	 */
	static Resp decode(ByteBuf buffer) {
		if (buffer.readableBytes() <= 0) {
			throw new RespIncompleteException();
		}
		char type = (char) buffer.readByte();

		if (type == RespType.STATUS.getCode()) {
			return new SimpleString(getString(buffer));
		} else if (type == RespType.ERROR.getCode()) {
			return new Errors(getString(buffer));
		} else if (type == RespType.INTEGER.getCode()) {
			return new RespInt(getNumber(buffer));
		} else if (type == RespType.BULK_STRING.getCode()) {
			int length = getLength(buffer, RespType.BULK_STRING.name());
			if (length == -1) {
				return BulkString.NIL;
			}
			// Bulk String 的内容后还必须有 \r\n；当前字节不足说明 TCP 半包尚未收全。
			if ((long) buffer.readableBytes() < (long)length + 2L) {
				throw new RespIncompleteException();
			}

			byte[] bytes = new byte[length];
			buffer.readBytes(bytes);

			if (buffer.readByte() != RespType.R.getCode() || buffer.readByte() != RespType.N.getCode()) {
				throw new IllegalStateException("没有读取到完整的命令");
			}
			return new BulkString(new BytesWrapper(bytes));
		} else if (type == RespType.ARRAY.getCode()) {
			int length = getLength(buffer, RespType.ARRAY.name());

			if (length == -1) {
				return RespArray.NIL;
			}
			Resp[] array = new Resp[length];
			for (int i = 0; i < length; i++) {
				array[i] = decode(buffer);
			}
			return new RespArray(array);
		} else {
			throw new IllegalStateException("未知RESP类型: " + type);
		}
	}

	/**
	 * 读取以CRLF结尾的RESP字符串内容。
	 *
	 * @param buffer 包含待读取数据的缓冲区
	 * @return 不包含结尾CRLF的字符串内容
	 * @throws RespIncompleteException 当尚未收到完整CRLF时抛出
	 * @throws IllegalStateException    当CR后紧跟的字节不是LF时抛出
	 */
	static String getString(ByteBuf buffer) {
		StringBuilder builder = new StringBuilder();
		byte current;
		while (buffer.readableBytes() > 0) {
			current = buffer.readByte();
			// 读取到 CR，下一字节必须是 LF
			if (current == RespType.R.getCode()) {
				break;
			}
			builder.append((char) current);
		}
		// 当前没有足够字节确认 CRLF，属于 TCP 半包
		if (buffer.readableBytes() == 0) {
			throw new RespIncompleteException();
		}
		// 已经收到了 CR 后的字节，但它不是 LF，才属于非法 RESP
		if (buffer.readByte() != RespType.N.getCode()) {
			throw new IllegalStateException("RESP字符串结尾必须是CRLF");
		}
		return builder.toString();
	}

	/**
	 * 读取 RESP 数字并转换为有符号64位整数。
	 *
	 * @param buffer 包含待读取数据的缓冲区
	 * @return 解析得到的 {@code long} 数值
	 * @throws IllegalStateException 当数字格式非法或超出 {@code long} 范围时抛出
	 */
	static long getNumber(ByteBuf buffer) {
		// getString 已经负责处理 CRLF 和 TCP 半包。
		String number = getString(buffer);

		try {
			// 标准库负责负号、非法字符和 long 溢出检测。
			return Long.parseLong(number);
		} catch (NumberFormatException e) {
			throw new IllegalStateException(
					"RESP数字格式非法: " + number,
					e
			);
		}
	}

	/**
	 * 将 RESP 长度转换为 Java 可用的 {@code int} 长度。
	 *
	 * @param buffer   包含待读取数据的缓冲区
	 * @param typeName 出错时用于提示的类型名称
	 * @return 转换后的长度，-1表示空值
	 * @throws RespIncompleteException 数据尚未包含完整长度及CRLF时抛出
	 * @throws IllegalStateException    长度不是整数、小于-1或超过 {@code Integer.MAX_VALUE} 时抛出
	 */
	private static int getLength(ByteBuf buffer, String typeName) {
		long length = getNumber(buffer);

		/*
		 * -1 表示 Null BulkString 或 Null Array。
		 * Java 数组和 ByteBuf 的索引使用 int，因此长度不能超过 Integer.MAX_VALUE。
		 */
		if (length < -1 || length > Integer.MAX_VALUE) {
			throw new IllegalStateException(
					typeName + "长度非法: " + length
			);
		}

		return (int) length;
	}
}
