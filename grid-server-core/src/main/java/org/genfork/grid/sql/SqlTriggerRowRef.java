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

import java.util.Locale;
import java.util.Objects;

/**
 * OLD.col / NEW.col slot captured while parsing a trigger body or WHEN fragment.
 * <p>
 * Resolved from wire row blobs via {@link org.genfork.grid.serial.LogicalFieldCursor}
 * at fire time — not a mid-pipeline full-row {@code Map}.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlTriggerRowRef {
	private final boolean oldRow;
	private final String column;

	public SqlTriggerRowRef(boolean oldRow, String column) {
		Objects.requireNonNull(column, "column");
		if (column.isBlank()) {
			throw new IllegalArgumentException("trigger row ref column required");
		}
		this.oldRow = oldRow;
		this.column = column;
	}

	public boolean oldRow() {
		return oldRow;
	}

	public String column() {
		return column;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof SqlTriggerRowRef r
				&& r.oldRow == oldRow
				&& r.column.equalsIgnoreCase(column);
	}

	@Override
	public int hashCode() {
		return Boolean.hashCode(oldRow) * 31 + column.toLowerCase(Locale.ROOT).hashCode();
	}

	@Override
	public String toString() {
		return (oldRow ? "OLD." : "NEW.") + column;
	}
}