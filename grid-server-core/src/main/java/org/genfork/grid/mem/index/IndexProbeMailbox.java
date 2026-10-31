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
package org.genfork.grid.mem.index;

import java.util.function.Supplier;

/**
 * Serial gate for RAM BPTree probes (same queue as index mutations).
 * <p>
 * Prevents concurrent {@code searchEq}/{@code search*} vs {@code keys.add}/{@code values.add}
 * TOCTOU ({@code IndexOutOfBoundsException}). Heal/seal dump must run outside the mailbox.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
@FunctionalInterface
public interface IndexProbeMailbox {
	/**
	 * Run {@code probe} on the index mailbox (re-entrant when already on-mailbox).
	 */
	<T> T call(Supplier<T> probe);

	/**
	 * Run {@code action} on the index mailbox.
	 */
	default void run(Runnable action) {
		call(() -> {
			action.run();
			return null;
		});
	}
}
