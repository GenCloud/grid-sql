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

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.genfork.grid.mem.ByteArrayContainer;
import org.genfork.grid.mem.index.AbstractIndexOperation;
import org.genfork.grid.mem.index.IndexPointerRef;
import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.mem.index.btree.TreeKey;

/**
 * Shared RAM + sealed fallback façade: ramAuthoritative flag and mutation delegation.
 * <p>
 * Probe / merge semantics stay in concrete subclasses ({@link SealedFallbackIndex},
 * {@link SealedFallbackCompositeIndex}, {@link SealedFallbackBitmap}).
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
public abstract class AbstractSealedFallbackIndex<K, T extends TreeKey<K>>
		extends AbstractIndexOperation<K, T> {
	private static final String SEALED_NAME_SUFFIX = "-SEALED";

	private final AbstractIndexOperation<K, T> delegate;
	/** FULL hydrate without eviction: non-empty RAM hits skip sealed merge. */
	private volatile boolean ramAuthoritative;

	protected AbstractSealedFallbackIndex(AbstractIndexOperation<K, T> delegate) {
		this.delegate = Objects.requireNonNull(delegate, "delegate");
	}

	/**
	 * When true, non-empty RAM results are authoritative (FULL hydrate, no WS eviction).
	 */
	public void setRamAuthoritative(boolean ramAuthoritative) {
		this.ramAuthoritative = ramAuthoritative;
	}

	public boolean ramAuthoritative() {
		return ramAuthoritative;
	}

	public AbstractIndexOperation<K, T> delegate() {
		return delegate;
	}

	/**
	 * FULL hydrate shortcut: skip sealed when RAM already returned postings.
	 */
	protected final boolean skipSealedOnRamHit(IndexOperationResult ram) {
		return ramAuthoritative
				&& ram != null
				&& ram.getPointers() != null
				&& !ram.getPointers().isEmpty();
	}

	/**
	 * Merge RAM pointers with sealed row keys (dedupe by key bytes; optional LIMIT).
	 */
	protected static IndexOperationResult mergeRamAndSealed(
			IndexOperationResult ram,
			List<byte[]> sealed,
			int maxResults
	) {
		final Map<ByteArrayContainer, IndexPointerRef> byKey = new LinkedHashMap<>();
		if (ram != null) {
			for (IndexPointerRef pointer : ram.pointersOrExpand()) {
				final byte[] key = pointer.resolveKey();
				if (key != null) {
					byKey.put(new ByteArrayContainer(key), pointer);
				}
			}
		}
		for (byte[] key : sealed) {
			if (maxResults > 0 && byKey.size() >= maxResults) {
				break;
			}
			byKey.putIfAbsent(new ByteArrayContainer(key), new IndexPointerRef(0, key));
		}
		final IndexOperationResult result = new IndexOperationResult();
		result.setPointers(new LinkedHashSet<>(byKey.values()));
		result.setSize(byKey.size());
		result.setRowsProcessed((ram == null ? 0 : ram.getRowsProcessed()) + sealed.size());
		return result;
	}

	@Override
	public String getIndexName() {
		return delegate.getIndexName() + SEALED_NAME_SUFFIX;
	}

	@Override
	public T createKey(String indexedProperty, byte[] value) {
		return delegate.createKey(indexedProperty, value);
	}

	@Override
	public T createKey(byte[][] value) {
		return delegate.createKey(value);
	}

	@Override
	public void insert(T key, IndexPointerRef pointerRef) {
		delegate.insert(key, pointerRef);
	}

	@Override
	public void delete(T key) {
		delegate.delete(key);
	}

	@Override
	public void deleteMatchingRowKey(T key, byte[] rowKey) {
		delegate.deleteMatchingRowKey(key, rowKey);
	}

	@Override
	public void deletePointer(T key, IndexPointerRef pointerRef) {
		delegate.deletePointer(key, pointerRef);
	}

	@Override
	public void clear() {
		delegate.clear();
	}

	@Override
	public void processAnalyze() {
		delegate.processAnalyze();
	}

	@Override
	public void dumpStats(String name) {
		delegate.dumpStats(name);
	}
}
