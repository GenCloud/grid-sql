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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.genfork.grid.mem.index.AbstractIndexOperation;
import org.genfork.grid.mem.index.btree.TreeKey;
import org.genfork.grid.mem.index.btree.comparator.ArraysComparator;

/**
 * Sealed {@code .sbpt} reader list + addReader for single/composite BPTree fallbacks.
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
public abstract class AbstractSealedBpTreeFallback<K, T extends TreeKey<K>>
		extends AbstractSealedFallbackIndex<K, T> {
	private static final String CLOSED_TOKEN = "closed";

	private final CopyOnWriteArrayList<SealedBPTreeReader> readers = new CopyOnWriteArrayList<>();

	protected AbstractSealedBpTreeFallback(AbstractIndexOperation<K, T> delegate) {
		super(delegate);
	}

	public void addReader(SealedBPTreeReader reader) {
		Objects.requireNonNull(reader, "reader");
		// Install next before dropping prior so concurrent probes never see a null-generation gap.
		readers.add(reader);
		readers.removeIf(existing -> existing != reader && existing.shard() == reader.shard());
	}

	/**
	 * {@code true} when at least one open sealed {@code .sbpt} reader is attached (portal-ineligible PK).
	 */
	public boolean hasSealedReaders() {
		return !openReaders().isEmpty();
	}

	int sealedReaderCount() {
		return openReaders().size();
	}

	protected final CopyOnWriteArrayList<SealedBPTreeReader> readers() {
		return readers;
	}

	protected final boolean readersEmpty() {
		readers.removeIf(SealedBPTreeReader::isClosed);
		return readers.isEmpty();
	}

	/**
	 * Drop closed readers from the COW list and collect still-open ones for sealed merge.
	 */
	protected final List<SealedBPTreeReader> openReaders() {
		readers.removeIf(SealedBPTreeReader::isClosed);
		if (readers.isEmpty()) {
			return List.of();
		}
		final List<SealedBPTreeReader> open = new ArrayList<>(readers.size());
		for (SealedBPTreeReader reader : readers) {
			if (reader != null && !reader.isClosed()) {
				open.add(reader);
			}
		}
		return open;
	}

	/**
	 * Merge sealed probe results from every open reader (skips closed; no ISE on query path).
	 * <p>
	 * TOCTOU: a reader may close after {@link #openReaders()} and before/during {@code probe};
	 * {@link SealedBPTreeReader} probes soft-miss when closed under read lock. Any residual
	 * closed ISE is pruned here so the query path stays fail-soft.
	 */
	protected final List<byte[]> collectFromOpenReaders(Function<SealedBPTreeReader, List<byte[]>> probe) {
		Objects.requireNonNull(probe, "probe");
		final List<SealedBPTreeReader> open = openReaders();
		if (open.isEmpty()) {
			return List.of();
		}
		final List<byte[]> sealed = new ArrayList<>();
		for (int i = 0, n = open.size(); i < n; i++) {
			final SealedBPTreeReader reader = open.get(i);
			try {
				final List<byte[]> part = probe.apply(reader);
				if (part != null && !part.isEmpty()) {
					sealed.addAll(part);
				}
			} catch (IllegalStateException closedRace) {
				if (!isClosedReaderFailure(closedRace)) {
					throw closedRace;
				}
				readers.remove(reader);
			}
		}
		return sealed;
	}

	private static boolean isClosedReaderFailure(Throwable error) {
		Throwable cursor = error;
		while (cursor != null) {
			final String message = cursor.getMessage();
			if (message != null && message.startsWith(SealedBPTreeReader.CLOSED_READER_MESSAGE_PREFIX)) {
				return true;
			}
			if (message != null && message.toLowerCase().contains(CLOSED_TOKEN)) {
				return true;
			}
			cursor = cursor.getCause();
		}
		return false;
	}

	/** Open-bound compare modes shared by single-column and composite sealed leaf walks. */
	protected enum OpenBound {
		GT, GE, LT, LE
	}

	private static final ArraysComparator KEY_ORDER = new ArraysComparator();

	protected static boolean openCmp(byte[] indexKey, byte[] bound, OpenBound mode) {
		final int cmp = KEY_ORDER.compare(indexKey, bound);
		return switch (mode) {
			case GT -> cmp > 0;
			case GE -> cmp >= 0;
			case LT -> cmp < 0;
			case LE -> cmp <= 0;
		};
	}
}
