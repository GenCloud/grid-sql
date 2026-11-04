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
package org.genfork.grid.replication.util;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Single-flight await helpers for LAZY shard hydrate.
 * <p>
 * <strong>Logic VT / SQL mailbox only</strong> — never call {@link #await} from Netty EL
 * or Reactor subscribe threads ({@code reactive-vt.mdc}).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
public final class LazyShardHydrateFlightUtil {
	private LazyShardHydrateFlightUtil() {
	}

	/**
	 * Park until {@code flight} completes; unwrap {@link CompletionException} causes.
	 *
	 * @param flight hydrate single-flight future
	 * @param key    domain#shard label for error text
	 */
	public static void await(CompletableFuture<Void> flight, String key) {
		Objects.requireNonNull(flight, "flight");
		try {
			flight.join();
		} catch (CompletionException ex) {
			final Throwable cause = ex.getCause() == null ? ex : ex.getCause();
			if (cause instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			if (cause instanceof Error error) {
				throw error;
			}
			final String label = key == null ? "" : key;
			throw new IllegalStateException("Lazy shard hydrate failed for " + label, cause);
		}
	}

	/**
	 * Drop in-flight entries whose keys match {@code keyPredicate} (e.g. domain prefix).
	 */
	public static void removeMatching(
			ConcurrentHashMap<String, CompletableFuture<Void>> flights,
			Predicate<String> keyPredicate
	) {
		Objects.requireNonNull(flights, "flights");
		Objects.requireNonNull(keyPredicate, "keyPredicate");
		flights.keySet().removeIf(key -> key != null && keyPredicate.test(key));
	}
}
