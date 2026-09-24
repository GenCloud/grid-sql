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
package org.genfork.grid.sql.client.transport;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.function.Consumer;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import org.genfork.grid.sql.client.ServerMeta;
import org.genfork.grid.sql.netty.SqlFrame;
import org.genfork.grid.sql.netty.SqlOpcode;
import org.genfork.grid.sql.netty.SqlWire;

/**
 * Demultiplexes server frames to pending collectors by requestId.
 * <p>
 * Demux only — Sync ({@link SyncExecExchange}, {@link SyncBatchExchange}) and reactive
 * exchanges share this handler. No Reactor types in the demux itself.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
@ChannelHandler.Sharable
public final class SqlClientInboundHandler extends SimpleChannelInboundHandler<SqlFrame> {
	private static final String ERR_SQL_PREFIX = "SQL error ";

	private final Map<Integer, PendingExchange> pending;
	private final Consumer<ServerMeta> serverMetaConsumer;

	public SqlClientInboundHandler(Map<Integer, PendingExchange> pending) {
		this(pending, ignored -> {
		});
	}

	public SqlClientInboundHandler(
			Map<Integer, PendingExchange> pending,
			Consumer<ServerMeta> serverMetaConsumer
	) {
		this.pending = pending;
		this.serverMetaConsumer = serverMetaConsumer == null ? ignored -> {
		} : serverMetaConsumer;
	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, SqlFrame frame) {
		if (frame.opcode() == SqlOpcode.PROMOTE_NOTIFY) {
			final ServerMeta serverMeta = SqlWire.readServerMeta(frame.payload());
			serverMetaConsumer.accept(serverMeta);
			return;
		}

		final PendingExchange ex = pending.get(frame.requestId());
		if (ex == null) {
			return;
		}

		switch (frame.opcode()) {
			case SqlOpcode.AUTH_OK -> {
				pending.remove(frame.requestId());
				final ServerMeta serverMeta = SqlWire.readServerMeta(frame.payload());
				serverMetaConsumer.accept(serverMeta);
				ex.completeAuth(serverMeta);
			}
			case SqlOpcode.SESSION_OPEN_OK -> {
				pending.remove(frame.requestId());
				ex.completeSessionOpen(SqlWire.readSessionId(frame.payload()));
			}
			case SqlOpcode.SESSION_CLOSE_OK -> {
				pending.remove(frame.requestId());
				ex.completeSessionClose();
			}
			case SqlOpcode.ERROR -> {
				pending.remove(frame.requestId());

				final SqlWire.ErrorPayload error = SqlWire.readError(frame.payload());
				if (error.hasServerMeta()) {
					serverMetaConsumer.accept(error.serverMeta());
				}
				ex.fail(new IllegalStateException(ERR_SQL_PREFIX + error.code() + ": " + error.message()));
			}
			case SqlOpcode.ROW_DESC -> ex.onRowDesc(SqlWire.decodeRowDesc(frame.payload()));
			case SqlOpcode.ROW_DATA -> ex.onRowData(SqlWire.decodeRowData(frame.payload()));
			case SqlOpcode.EXEC_DONE -> {
				final ByteBuffer buf = ByteBuffer.wrap(frame.payload()).order(ByteOrder.LITTLE_ENDIAN);
				final long affected = buf.getLong();
				final String tag = SqlWire.readUtf8(buf);
				if (ex.completeExec(affected, tag)) {
					pending.remove(frame.requestId());
				}
			}
			default -> {
			}
		}
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		for (PendingExchange ex : pending.values()) {
			ex.fail(cause instanceof Exception e ? e : new IllegalStateException(cause));
		}

		pending.clear();
		ctx.close();
	}
}
