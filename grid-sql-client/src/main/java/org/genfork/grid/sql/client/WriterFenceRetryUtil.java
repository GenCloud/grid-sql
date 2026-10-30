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
package org.genfork.grid.sql.client;

import java.util.Objects;
import java.util.function.Supplier;

import org.genfork.grid.common.WriterFenceSignals;

/**
 * One transparent retry after orchid / region / apply-lag writer fence.
 * <p>
 * Shared by sync ({@link org.genfork.grid.sql.client.sync.SyncSession}) and JDBC
 * ({@link org.genfork.grid.jdbc.GridStatement}).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
public final class WriterFenceRetryUtil {
	private WriterFenceRetryUtil() {
	}

	/**
	 * Run {@code operation}; on {@link WriterFenceSignals#requiresWriterRediscover} invoke
	 * {@code rediscover} once and retry {@code operation}.
	 */
	public static <T> T callWithOneRediscover(Runnable rediscover, Supplier<T> operation) {
		Objects.requireNonNull(rediscover, "rediscover");
		Objects.requireNonNull(operation, "operation");
		try {
			return operation.get();
		} catch (RuntimeException first) {
			if (!WriterFenceSignals.requiresWriterRediscover(first)) {
				throw first;
			}
			rediscover.run();
			return operation.get();
		}
	}
}
