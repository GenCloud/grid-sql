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

import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import java.util.function.Supplier;

import io.netty.channel.ChannelHandlerContext;

import org.genfork.grid.sql.SqlReplicaAdmission;
import org.genfork.grid.sql.client.ServerMeta;

/**
 * Shared EXEC / BATCH_EXEC error and row-window framing for {@link SqlExecHandler}.
 * <p>
 * Keeps BATCH fail-fast on the same ERROR / cancel-intent path as single EXEC.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
final class SqlExecDispatchSupport {
	private SqlExecDispatchSupport() {
	}

	/**
	 * Build ERROR payload for an EXEC / BATCH_EXEC failure (replica admission codes + meta).
	 */
	static byte[] execErrorPayload(Exception ex, ServerMeta meta) {
		final String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
		if (message.contains("Index ") && message.contains("out of bounds")
				|| ex instanceof IndexOutOfBoundsException
				|| ex instanceof ArrayIndexOutOfBoundsException
				|| (ex.getCause() instanceof IndexOutOfBoundsException)
				|| (ex.getCause() instanceof ArrayIndexOutOfBoundsException)) {
			ex.printStackTrace(System.err);
		}
		final int code = SqlReplicaAdmission.wireErrorCode(ex);
		return SqlWire.error(code, message, meta);
	}

	/**
	 * Shared cancel-intent + finish + ERROR write used by EXEC and BATCH_EXEC catch paths.
	 *
	 * @return {@code true} when the request was cancelled (caller must not write ERROR)
	 */
	static boolean finishCancelledOrWriteError(
			ChannelHandlerContext ctx,
			int requestId,
			Exception ex,
			IntPredicate consumeCancelIntent,
			IntConsumer finishRequest,
			Supplier<ServerMeta> serverMeta
	) {
		final boolean cancelled = consumeCancelIntent.test(requestId);
		finishRequest.accept(requestId);
		if (cancelled) {
			return true;
		}
		final byte[] payload = execErrorPayload(ex, serverMeta.get());
		ctx.executor().execute(() -> ctx.writeAndFlush(new SqlFrame(SqlOpcode.ERROR, requestId, payload)));
		return false;
	}

	/** Write up to {@code max} ROW_DATA frames starting at {@code from}. */
	static int writeRowWindow(
			ChannelHandlerContext ctx,
			int requestId,
			List<Object[]> rows,
			int from,
			int max
	) {
		final int end = Math.min(rows.size(), from + Math.max(0, max));
		int written = 0;
		for (int i = from; i < end; i++) {
			final Object[] row = rows.get(i);
			SqlFrames.write(ctx, SqlOpcode.ROW_DATA, requestId, out -> SqlWire.rowDataInto(out, row));
			written++;
		}
		return written;
	}
}