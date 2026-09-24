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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.netty.channel.ChannelHandlerContext;

/**
 * Tracks active SQL handlers and fans promotion metadata out on their Netty event loops.
 * <p>
 * The coordinator invokes {@link #pushPromoteNotify()} on a logic virtual thread. Each write is
 * then enqueued onto the owning channel event loop without waiting for it.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
final class SqlChannelRegistry {
	private final Map<SqlExecHandler, ChannelHandlerContext> channels = new ConcurrentHashMap<>();

	void register(SqlExecHandler handler, ChannelHandlerContext context) {
		if (handler != null && context != null) {
			channels.put(handler, context);
		}
	}

	void unregister(SqlExecHandler handler) {
		if (handler != null) {
			channels.remove(handler);
		}
	}

	void pushPromoteNotify() {
		for (Map.Entry<SqlExecHandler, ChannelHandlerContext> entry : channels.entrySet()) {
			final SqlExecHandler handler = entry.getKey();
			final ChannelHandlerContext context = entry.getValue();
			context.executor().execute(() -> handler.pushPromoteNotify(context));
		}
	}
}