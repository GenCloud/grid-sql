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

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.genfork.grid.mem.index.btree.AbstractBPTree;
import org.genfork.grid.sql.SqlRowWindowSource;
import org.genfork.grid.store.TableStore;

/**
 * AlwaysTrue / no-ORDER SELECT pull cursor: PK leaf walk + project per FETCH window.
 * <p>
 * Holds at most one FETCH window of projected rows on the wire path (no full List of Object[]).
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class PkLeafSelectRowWindowSource implements SqlRowWindowSource {
	private static final int UNBOUNDED = Integer.MAX_VALUE;

	private final TableStore store;
	private final List<String> projection;
	private final AbstractBPTree.RowKeyCursor<?, ?> keyCursor;
	private final int offset;
	private final int limit;
	private int skipped;
	private int accepted;
	private long emitted;
	private final AtomicBoolean closed = new AtomicBoolean(false);
	private boolean exhausted;

	public PkLeafSelectRowWindowSource(
			TableStore store,
			List<String> projection,
			int offset,
			Integer limitOrNull
	) {
		this.store = Objects.requireNonNull(store, "store");
		this.projection = Objects.requireNonNull(projection, "projection");
		this.keyCursor = store.openPrimaryKeyRowCursor();
		this.offset = Math.max(0, offset);
		this.limit = limitOrNull == null ? UNBOUNDED : Math.max(0, limitOrNull);
		if (this.limit == 0) {
			exhausted = true;
			close();
		}
	}

	@Override
	public int fillWindow(List<Object[]> dest, int maxRows) {
		if (closed.get() || exhausted || maxRows <= 0) {
			return 0;
		}
		int written = 0;
		while (written < maxRows && keyCursor.hasNext()) {
			if (limit != UNBOUNDED && accepted >= limit) {
				exhausted = true;
				close();
				break;
			}
			final byte[] key = keyCursor.next();
			final byte[] value = store.getCommittedBytes(key);
			if (value == null) {
				continue;
			}
			if (skipped < offset) {
				skipped++;
				continue;
			}
			dest.add(store.projectBytes(value, projection));
			written++;
			accepted++;
			emitted++;
		}
		if (!keyCursor.hasNext() || (limit != UNBOUNDED && accepted >= limit)) {
			exhausted = true;
			close();
		}
		return written;
	}

	@Override
	public boolean exhausted() {
		return exhausted || closed.get();
	}

	@Override
	public long emitted() {
		return emitted;
	}

	@Override
	public void close() {
		if (closed.compareAndSet(false, true)) {
			exhausted = true;
			keyCursor.close();
		}
	}
}