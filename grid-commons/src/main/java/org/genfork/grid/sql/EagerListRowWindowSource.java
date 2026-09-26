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

import java.util.List;
import java.util.Objects;

/**
 * Eager {@link List} adapter implementing {@link SqlRowWindowSource}.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class EagerListRowWindowSource implements SqlRowWindowSource {
	private final List<Object[]> rows;
	private int index;
	private long emitted;
	private boolean closed;

	public EagerListRowWindowSource(List<Object[]> rows) {
		this.rows = Objects.requireNonNull(rows, "rows");
	}

	public static EagerListRowWindowSource of(List<Object[]> rows) {
		return new EagerListRowWindowSource(rows);
	}

	@Override
	public int fillWindow(List<Object[]> dest, int maxRows) {
		if (closed || maxRows <= 0) {
			return 0;
		}
		final int end = Math.min(rows.size(), index + maxRows);
		int written = 0;
		for (int i = index; i < end; i++) {
			dest.add(rows.get(i));
			written++;
		}
		index = end;
		emitted += written;
		return written;
	}

	@Override
	public boolean exhausted() {
		return closed || index >= rows.size();
	}

	@Override
	public long emitted() {
		return emitted;
	}

	@Override
	public void close() {
		closed = true;
	}
}