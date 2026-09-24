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
package org.genfork.grid.sql.exec;

import java.util.Arrays;
import java.util.List;

import org.genfork.grid.catalog.FkDef;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.mem.index.GridCompositeIndex;
import org.genfork.grid.serial.LogicalFieldCursor;
import org.genfork.grid.store.TableStore;

/**
 * FOREIGN KEY child wire-EQ match helpers for {@link SqlDmlExecutor}.
 * <p>
 * Child matches use indexed wire EQ on child columns (no full-table key list). Snapshot
 * keys before {@code action} so CASCADE/SET NULL deletes do not CME the index iterator.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlFkMatchOps {
	private SqlFkMatchOps() {
	}

	/**
	 * Action over one matched child row (key + value blob + open cursor).
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	@FunctionalInterface
	public interface ChildFkAction {
		void accept(byte[] key, byte[] value, LogicalFieldCursor cursor);
	}

	static void forEachChildFkMatch(
			TableStore child,
			TableSchema childSchema,
			FkDef fk,
			byte[][] want,
			ChildFkAction action
	) {
		if (!child.hasEqIndex(fk.childColumns())) {
			throw new IllegalStateException(
					GridCompositeIndex.MSG_EQ_REQUIRES_INDEX + ": " + fk.name() + " " + fk.childColumns());
		}
		final List<byte[]> matchedKeys = child.lookupEqKeys(fk.childColumns(), want);
		for (byte[] key : matchedKeys) {
			final byte[] value = child.getCommittedBytes(key);
			if (value == null || !LogicalFieldCursor.canOpen(childSchema, value)) {
				continue;
			}
			final LogicalFieldCursor cursor = LogicalFieldCursor.open(childSchema, value);
			if (foreignKeyMatches(childSchema, fk, cursor, want)) {
				action.accept(key, value, cursor);
			}
		}
	}

	static boolean foreignKeyMatches(
			TableSchema childSchema,
			FkDef fk,
			LogicalFieldCursor childCursor,
			byte[][] parentValues
	) {
		for (int i = 0; i < fk.childColumns().size(); i++) {
			final int ordinal = childSchema.requireColumn(fk.childColumns().get(i)).ordinal();
			if (!Arrays.equals(parentValues[i], childCursor.indexKeyBytes(ordinal))) {
				return false;
			}
		}
		return true;
	}
}
