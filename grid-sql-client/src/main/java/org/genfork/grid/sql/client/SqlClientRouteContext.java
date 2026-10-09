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
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Per-thread request-context override for client read routing.
 * <p>
 * Default {@link Option#none()} → URL / {@link ConnectionOptions} behavior.
 * {@link #forcePrimary()} suppresses READ_REPLICA autocommit for the current thread
 * (Flyway migrate, DDL tooling, or any caller that must hit the proposer only).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.1
 */
public final class SqlClientRouteContext {
	private static final ThreadLocal<Option<ReadPreference>> OVERRIDE =
			ThreadLocal.withInitial(Option::none);

	private SqlClientRouteContext() {
	}

	/**
	 * Current thread override; empty means no request-context pin.
	 */
	public static Option<ReadPreference> current() {
		return OVERRIDE.get();
	}

	/**
	 * {@code true} when this thread must not borrow the READ_REPLICA pool.
	 */
	public static boolean suppressReplicaReads() {
		final Option<ReadPreference> cur = OVERRIDE.get();
		return cur.isPresent() && cur.get() == ReadPreference.PRIMARY;
	}

	/**
	 * Push {@link ReadPreference#PRIMARY} for this thread; restore previous on {@link AutoCloseable#close()}.
	 */
	public static AutoCloseable forcePrimary() {
		return push(ReadPreference.PRIMARY);
	}

	/**
	 * Run {@code action} with {@link #forcePrimary()} scope.
	 */
	public static void runWithPrimary(Runnable action) {
		Objects.requireNonNull(action, "action");
		try (AutoCloseable ignored = forcePrimary()) {
			action.run();
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Call {@code action} with {@link #forcePrimary()} scope.
	 */
	public static <T> T callWithPrimary(Callable<T> action) throws Exception {
		Objects.requireNonNull(action, "action");
		try (AutoCloseable ignored = forcePrimary()) {
			return action.call();
		}
	}

	/**
	 * Supplier form of {@link #callWithPrimary(Callable)} (unchecked).
	 */
	public static <T> T getWithPrimary(Supplier<T> action) {
		Objects.requireNonNull(action, "action");
		try (AutoCloseable ignored = forcePrimary()) {
			return action.get();
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static AutoCloseable push(ReadPreference preference) {
		Objects.requireNonNull(preference, "preference");
		final Option<ReadPreference> previous = OVERRIDE.get();
		OVERRIDE.set(Option.some(preference));
		return () -> OVERRIDE.set(previous);
	}
}
