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
package org.genfork.grid.sql.client.reactive;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Gate: reactive exchanges must not hold CompletableFuture completion state.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
class ReactiveExchangeNoCfBridgeTest {
	@Test
	void reactiveExecExchangeHasNoCompletableFutureFields() {
		assertNoCfFields(ReactiveExecExchange.class);
	}

	@Test
	void reactiveBatchExchangeHasNoCompletableFutureFields() {
		assertNoCfFields(ReactiveBatchExchange.class);
	}

	@Test
	void transportReactiveHasNoCompletableFutureFields() {
		assertNoCfFields(TransportReactive.class);
	}

	private static void assertNoCfFields(Class<?> type) {
		Class<?> cur = type;
		while (cur != null && cur != Object.class) {
			for (Field field : cur.getDeclaredFields()) {
				assertFalse(
						CompletableFuture.class.isAssignableFrom(field.getType()),
						() -> type.getSimpleName() + '.' + field.getName() + " must not be CompletableFuture");
			}
			cur = cur.getSuperclass();
		}
	}
}