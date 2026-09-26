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
package org.genfork.grid.sql;

import java.util.ArrayList;
import java.util.List;

/**
 * Pull-based row window for RESULT_SET portals (FETCH without full materialize).
 * <p>
 * Wire path fills {@code dest} up to {@code maxRows}; {@link #close()} is idempotent.
 * In-process callers may {@link #drainAll()} once.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public interface SqlRowWindowSource extends AutoCloseable {
	/**
	 * Append up to {@code maxRows} projected rows into {@code dest}.
	 *
	 * @return number of rows appended
	 */
	int fillWindow(List<Object[]> dest, int maxRows);

	/** {@code true} when no more rows will be produced. */
	boolean exhausted();

	/** Rows emitted so far (across all fillWindow calls). */
	long emitted();

	/** Idempotent release of leaf / index resources. */
	@Override
	void close();

	/** Materialize remaining rows (SPI / in-process). Closes the source. */
	default List<Object[]> drainAll() {
		final List<Object[]> all = new ArrayList<>();
		while (!exhausted()) {
			final int n = fillWindow(all, Integer.MAX_VALUE);
			if (n <= 0) {
				break;
			}
		}
		close();
		return all;
	}
}