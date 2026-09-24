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
package org.genfork.grid.replication.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;

import java.util.List;

/**
 * Frame: uint32 BE length (opcode+body) | uint8 opcode | body.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class ReplicationFrameCodec {
	private ReplicationFrameCodec() {
	}

	public record WireMessage(int opcode, byte[] body) {
	}

	public static final class Encoder extends MessageToByteEncoder<WireMessage> {
		@Override
		protected void encode(ChannelHandlerContext ctx, WireMessage msg, ByteBuf out) {
			final byte[] body = msg.body() == null ? new byte[0] : msg.body();
			out.writeInt(1 + body.length);
			out.writeByte(msg.opcode());
			out.writeBytes(body);
		}
	}

	public static final class Decoder extends ByteToMessageDecoder {
		private final int maxFrameBytes;

		public Decoder(int maxFrameBytes) {
			this.maxFrameBytes = maxFrameBytes;
		}

		@Override
		protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
			if (in.readableBytes() < 4) {
				return;
			}
			in.markReaderIndex();
			final int len = in.readInt();
			if (len <= 0 || len > maxFrameBytes) {
				ctx.close();
				return;
			}
			if (in.readableBytes() < len) {
				in.resetReaderIndex();
				return;
			}
			final int opcode = in.readUnsignedByte();
			final byte[] body = new byte[len - 1];
			in.readBytes(body);
			out.add(new WireMessage(opcode, body));
		}
	}
}
