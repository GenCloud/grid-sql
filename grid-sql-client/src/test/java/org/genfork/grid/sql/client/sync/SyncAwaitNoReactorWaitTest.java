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
package org.genfork.grid.sql.client.sync;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SyncAwait must not offer Mono/Flux wait APIs (forces Mono.toFuture anti-pattern).
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
class SyncAwaitNoReactorWaitTest {
	@Test
	void syncAwaitHasNoMonoOrFluxOverloads() {
		final List<String> offenders = new ArrayList<>();
		for (Method method : SyncAwait.class.getDeclaredMethods()) {
			for (Class<?> param : method.getParameterTypes()) {
				if (Mono.class.isAssignableFrom(param) || Flux.class.isAssignableFrom(param)) {
					offenders.add(method.toGenericString());
				}
			}
		}
		assertTrue(offenders.isEmpty(),
				() -> "SyncAwait must not accept Mono/Flux: " + offenders);
	}
}