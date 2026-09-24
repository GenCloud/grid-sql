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

import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.mem.index.btree.TreeKey;

/**
 * @author: GenCloud
 * @date: 2025/04
 * @since: 1.0
 */
public abstract class AbstractIndexOperation<K, T extends TreeKey<K>> {
	public abstract String getIndexName();

	public abstract T createKey(String indexedProperty, byte[] value);

	public abstract T createKey(byte[][] value);

	public abstract void insert(T key, IndexPointerRef pointerRef);

	public abstract void delete(T key);

	/**
	 * Remove a single posting under {@code key}. Prefer this over {@link #delete} for LAX upserts.
	 */
	public void deletePointer(T key, IndexPointerRef pointerRef) {
		if (pointerRef == null) {
			delete(key);
			return;
		}
		deleteMatchingRowKey(key, pointerRef.resolveKey());
	}

	/**
	 * Remove posting(s) for one row key under {@code key} without deleting other LAX peers
	 * that share the same index value. Default falls back to full {@link #delete}.
	 */
	public void deleteMatchingRowKey(T key, byte[] rowKey) {
		delete(key);
	}

	public abstract void clear();

	public abstract IndexOperationResult searchEq(T key);

	/**
	 * EQ search that may stop after {@code maxResults} pointers (0 = unlimited).
	 * Default collects the full posting set.
	 */
	public IndexOperationResult searchEq(T key, int maxResults) {
		return searchEq(key);
	}

	public abstract IndexOperationResult searchNotEq(T key);

	public abstract IndexOperationResult searchLike(T pattern);

	public abstract IndexOperationResult searchGreaterThan(T key);

	public abstract IndexOperationResult searchGreaterThanOrEqual(T key);

	public abstract IndexOperationResult searchLessThan(T key);

	public abstract IndexOperationResult searchLessThanOrEqual(T key);

	public abstract IndexOperationResult searchRange(T low, T high);

	public abstract IndexOperationResult searchAll();

	public void processAnalyze() {
		//
	}

	public void dumpStats(String name) {
	}
}
