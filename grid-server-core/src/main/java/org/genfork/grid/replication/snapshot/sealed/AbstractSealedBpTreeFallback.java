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
package org.genfork.grid.replication.snapshot.sealed;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import com.google.common.annotations.VisibleForTesting;

import org.genfork.grid.mem.index.AbstractIndexOperation;
import org.genfork.grid.mem.index.btree.TreeKey;

/**
 * Sealed {@code .sbpt} reader list + addReader for single/composite BPTree fallbacks.
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
public abstract class AbstractSealedBpTreeFallback<K, T extends TreeKey<K>>
		extends AbstractSealedFallbackIndex<K, T> {
	private final CopyOnWriteArrayList<SealedBPTreeReader> readers = new CopyOnWriteArrayList<>();

	protected AbstractSealedBpTreeFallback(AbstractIndexOperation<K, T> delegate) {
		super(delegate);
	}

	public void addReader(SealedBPTreeReader reader) {
		Objects.requireNonNull(reader, "reader");
		readers.removeIf(existing -> existing.shard() == reader.shard());
		readers.add(reader);
	}

	@VisibleForTesting
	public int sealedReaderCount() {
		return readers.size();
	}

	protected final CopyOnWriteArrayList<SealedBPTreeReader> readers() {
		return readers;
	}

	protected final boolean readersEmpty() {
		return readers.isEmpty();
	}

	/** Open-bound compare modes shared by single-column and composite sealed leaf walks. */
	protected enum OpenBound {
		GT, GE, LT, LE
	}

	protected static boolean openCmp(byte[] indexKey, byte[] bound, OpenBound mode) {
		final int cmp = SealedIndexKeyOrder.compareUnsigned(indexKey, bound);
		return switch (mode) {
			case GT -> cmp > 0;
			case GE -> cmp >= 0;
			case LT -> cmp < 0;
			case LE -> cmp <= 0;
		};
	}
}
