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
package org.genfork.grid.mem.index.btree;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.genfork.grid.mem.index.IndexPointerRef;
import org.genfork.grid.mem.index.bitmap.Bitmap;

/**
 * @author: GenCloud
 * @date: 2025/04
 * @since: 1.0
 */
public class IndexOperationResult {
	public static final IndexOperationResult EMPTY = new IndexOperationResult(Collections.emptySet());

	private Set<IndexPointerRef> pointers;

	private long size;
	private long rowsProcessed;

	/**
	 * Optional compact bitmap from {@link org.genfork.grid.mem.index.bitmap.GridBitmapIndex}
	 * before expansion to pointer sets. AND/OR with the same position map can combine BitSets first.
	 */
	private Bitmap bitmap;
	private Map<Integer, IndexPointerRef> bitmapPositions;

	public IndexOperationResult() {
	}

	public IndexOperationResult(Set<IndexPointerRef> pointers) {
		this.pointers = pointers;
		size = pointers.size();
		rowsProcessed = 1;
	}

	public Set<IndexPointerRef> getPointers() {
		return pointers;
	}

	public void setPointers(Set<IndexPointerRef> pointers) {
		this.pointers = pointers;
	}

	public long getSize() {
		return size;
	}

	public void setSize(long size) {
		this.size = size;
	}

	public long getRowsProcessed() {
		return rowsProcessed;
	}

	public void setRowsProcessed(long rowsProcessed) {
		this.rowsProcessed = rowsProcessed;
	}

	public Bitmap getBitmap() {
		return bitmap;
	}

	public void setBitmap(Bitmap bitmap) {
		this.bitmap = bitmap;
	}

	public Map<Integer, IndexPointerRef> getBitmapPositions() {
		return bitmapPositions;
	}

	public void setBitmapPositions(Map<Integer, IndexPointerRef> bitmapPositions) {
		this.bitmapPositions = bitmapPositions;
	}

	public void incSize() {
		size++;
	}

	public void addSize(long size) {
		this.size += size;
	}

	public void incProcessed() {
		rowsProcessed++;
	}

	public void addProcessed(long rowsProcessed) {
		this.rowsProcessed += rowsProcessed;
	}

	public void join(IndexOperationResult result) {
		pointers = result.pointers;
		size += result.size;
		rowsProcessed += result.rowsProcessed;
		bitmap = result.bitmap;
		bitmapPositions = result.bitmapPositions;
	}

	public boolean hasBitmap() {
		return bitmap != null && bitmapPositions != null;
	}

	/** Expand deferred bitmap into {@link #pointers} once. */
	public void expandBitmapPointers() {
		if (!hasBitmap()) {
			return;
		}
		if (pointers != null && !pointers.isEmpty()) {
			return;
		}
		final Set<IndexPointerRef> result = new HashSet<>();
		bitmap.forEachSetBit(position -> {
			final IndexPointerRef pointer = bitmapPositions.get(position);
			if (pointer != null) {
				result.add(pointer);
			}
		});
		pointers = result;
		size = result.size();
	}

	public Set<IndexPointerRef> pointersOrExpand() {
		if ((pointers == null || pointers.isEmpty()) && hasBitmap()) {
			expandBitmapPointers();
		}
		return pointers == null ? Collections.emptySet() : pointers;
	}
}
