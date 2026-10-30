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
package org.genfork.grid.jdbc;

import org.genfork.grid.common.WriterFenceSignals;
import org.genfork.grid.sql.client.WriterFenceRetryUtil;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * JDBC fence retry: one rediscover then re-execute.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
public class JdbcWriterFenceRetryUtilTest {
	private static final String OK = "ok";

	@Test
	void successWithoutRediscover() {
		final AtomicInteger rediscovers = new AtomicInteger();
		final String value = WriterFenceRetryUtil.callWithOneRediscover(
				rediscovers::incrementAndGet,
				() -> OK);
		assertEquals(OK, value);
		assertEquals(0, rediscovers.get());
	}

	@Test
	void fenceThenSuccessAfterRediscover() {
		final AtomicInteger rediscovers = new AtomicInteger();
		final AtomicInteger attempts = new AtomicInteger();
		final String value = WriterFenceRetryUtil.callWithOneRediscover(
				rediscovers::incrementAndGet,
				() -> {
					if (attempts.getAndIncrement() == 0) {
						throw new IllegalStateException(WriterFenceSignals.STALE_REPLICA_READ);
					}
					return OK;
				});
		assertEquals(OK, value);
		assertEquals(1, rediscovers.get());
		assertEquals(2, attempts.get());
	}

	@Test
	void nonFencePropagatesWithoutRediscover() {
		final AtomicInteger rediscovers = new AtomicInteger();
		assertThrows(IllegalStateException.class, () ->
				WriterFenceRetryUtil.callWithOneRediscover(
						rediscovers::incrementAndGet,
						() -> {
							throw new IllegalStateException("other");
						}));
		assertEquals(0, rediscovers.get());
	}
}
