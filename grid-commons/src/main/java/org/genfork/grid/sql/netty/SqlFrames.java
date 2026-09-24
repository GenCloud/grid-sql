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
import io.netty.channel.ChannelPromise;

import java.util.function.Consumer;

/**
 * Write LE SQL frames straight into pooled {@link ByteBuf} (bypass {@link SqlFrame} {@code byte[]}).
 * <p>
 * Use for WireLarge outbound (ROW_DATA / ROW_DESC / large BATCH) where encodeInto wins.
 * Tiny AUTH/SESSION/CANCEL stay on {@link SqlFrame} + heap {@code allocateWireLe}.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlFrames {
	/** Initial pooled ByteBuf capacity hint for frame header + small payload. */
	private static final int INITIAL_CAPACITY = 64;

	private SqlFrames() {
	}

	/**
	 * Encode {@code u32 frameLen | u8 opcode | u32 requestId | payload} into a pooled buffer and write.
	 * Passes through the pipeline as {@link ByteBuf} (codec skips non-{@link SqlFrame} outbound).
	 */
	public static void write(
			ChannelHandlerContext ctx,
			byte opcode,
			int requestId,
			Consumer<ByteBuf> payloadWriter
	) {
		write(ctx, opcode, requestId, payloadWriter, ctx.voidPromise());
	}

	/**
	 * Client/server channel write without {@link ChannelHandlerContext} (WireLarge BATCH_EXEC).
	 */
	public static void writeAndFlush(
			io.netty.channel.Channel channel,
			byte opcode,
			int requestId,
			Consumer<ByteBuf> payloadWriter
	) {
		final ByteBuf buf = channel.alloc().buffer(INITIAL_CAPACITY);
		final int start = buf.writerIndex();
		buf.writeIntLE(0);
		buf.writeByte(opcode);
		buf.writeIntLE(requestId);
		payloadWriter.accept(buf);
		final int frameLen = buf.writerIndex() - start - Integer.BYTES;
		if (frameLen <= 0 || frameLen > SqlOpcode.MAX_FRAME) {
			buf.release();
			throw new IllegalArgumentException("bad frameLen " + frameLen);
		}
		buf.setIntLE(start, frameLen);
		channel.writeAndFlush(buf);
	}

	private static void write(
			ChannelHandlerContext ctx,
			byte opcode,
			int requestId,
			Consumer<ByteBuf> payloadWriter,
			ChannelPromise promise
	) {
		final ByteBuf buf = ctx.alloc().buffer(INITIAL_CAPACITY);
		final int start = buf.writerIndex();
		buf.writeIntLE(0);
		buf.writeByte(opcode);
		buf.writeIntLE(requestId);
		payloadWriter.accept(buf);
		final int frameLen = buf.writerIndex() - start - Integer.BYTES;
		if (frameLen <= 0 || frameLen > SqlOpcode.MAX_FRAME) {
			buf.release();
			promise.setFailure(new IllegalArgumentException("bad frameLen " + frameLen));
			return;
		}
		buf.setIntLE(start, frameLen);
		ctx.write(buf, promise);
	}
}
