/*
 * Copyright 2024-2026 GenCloud
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.genfork.grid.sql.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;

import java.util.List;

/**
 * LE framing: {@code u32 frameLen | u8 opcode | u32 requestId | payload}.
 * {@code frameLen} = bytes after the length field (1 + 4 + payload).
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlFrameCodec extends ByteToMessageCodec<SqlFrame> {
	private static final int FRAME_HEADER_BYTES = 1 + 4;

	private static final String BAD_FRAME_HINT =
			" (not SQL TCP? use grid.sql-server port, default 15432 — not replication bind-port 5615)";

	@Override
	protected void encode(ChannelHandlerContext ctx, SqlFrame msg, ByteBuf out) {
		final byte[] payload = msg.payload() == null ? new byte[0] : msg.payload();
		final int frameLen = FRAME_HEADER_BYTES + payload.length;
		if (frameLen > SqlOpcode.MAX_FRAME) {
			throw new IllegalArgumentException("frame too large: " + frameLen);
		}
		out.writeIntLE(frameLen);
		out.writeByte(msg.opcode());
		out.writeIntLE(msg.requestId());
		out.writeBytes(payload);
	}

	@Override
	protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
		if (in.readableBytes() < Integer.BYTES) {
			return;
		}
		in.markReaderIndex();
		final int frameLen = in.readIntLE();
		if (frameLen <= 0 || frameLen > SqlOpcode.MAX_FRAME) {
			throw new IllegalArgumentException("bad frameLen " + frameLen + BAD_FRAME_HINT);
		}
		if (in.readableBytes() < frameLen) {
			in.resetReaderIndex();
			return;
		}
		final byte opcode = in.readByte();
		final int requestId = in.readIntLE();
		final int payloadLen = frameLen - FRAME_HEADER_BYTES;
		final byte[] payload = new byte[payloadLen];
		in.readBytes(payload);
		out.add(new SqlFrame(opcode, requestId, payload));
	}
}