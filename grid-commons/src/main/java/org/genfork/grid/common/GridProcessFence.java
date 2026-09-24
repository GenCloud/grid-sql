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
package org.genfork.grid.common;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide fail-closed latch for fatal runtime invariants (e.g. commonPool abuse).
 * <p>
 * Once tripped, admit paths must reject new work with an explicit error — never silent stall.
 *
 * @author: GenCloud
 * @date: 2025/02
 * @since: 1.0
 */
public final class GridProcessFence {

	private static final AtomicBoolean TRIPPED = new AtomicBoolean(false);
	private static final AtomicReference<String> REASON = new AtomicReference<>();

	private GridProcessFence() {
	}

	/**
	 * Trip the fence once; subsequent trips keep the first reason.
	 *
	 * @return true if this call was the first trip
	 */
	public static boolean trip(String reason) {
		final String safe = reason == null ? "unknown" : reason;
		if (TRIPPED.compareAndSet(false, true)) {
			REASON.set(safe);
			return true;
		}
		return false;
	}

	public static boolean isTripped() {
		return TRIPPED.get();
	}

	public static String reasonOrEmpty() {
		final String r = REASON.get();
		return r == null ? "" : r;
	}

	/** Test hook only. */
	public static void resetForTests() {
		REASON.set(null);
		TRIPPED.set(false);
	}

	public static void checkAdmit(String where) {
		if (!TRIPPED.get()) {
			return;
		}
		final String r = reasonOrEmpty();
		throw new IllegalStateException("GridProcessFence tripped at " + where + ": " + r);
	}
}