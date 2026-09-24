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

import java.util.List;

import org.springframework.lang.NonNull;

import org.genfork.grid.serial.WireLikeMatcher;

/**
 * @author: GenCloud
 * @date: 2025/04
 * @since: 1.0
 */
public class GridPointerCompositeBPTree extends AbstractBPTree<byte[][], CompositeTreeKey> {
	private final List<String> indexFields;

	public GridPointerCompositeBPTree(List<String> indexFields, String indexName, boolean strict) {
		super(indexName, strict);
		this.indexFields = indexFields;
	}

	@Override
	public CompositeTreeKey createKey(String indexedProperty, byte[] value) {
		final int idx = indexFields.indexOf(indexedProperty);

		final byte[][] key = new byte[1][];
		key[0] = value;

		return new CompositeTreeKey(key, idx);
	}

	@Override
	public CompositeTreeKey createKey(byte[][] value) {
		return new CompositeTreeKey(value);
	}

	@Override
	protected int getKeyMemorySize(CompositeTreeKey key) {
		int size = 0;
		for (byte[] bytes : key.getKey()) {
			size += Long.BYTES * 2 + bytes.length;
		}

		return size;
	}

	@Override
	@NonNull
	protected InternalNode<byte[][], CompositeTreeKey> createInternalNode() {
		return new DefaultInternalNode();
	}

	@Override
	@NonNull
	protected LeafNode<byte[][], CompositeTreeKey> createLeafNode() {
		return new DefaultLeafNode();
	}

	@Override
	protected boolean matchesPattern(CompositeTreeKey bytes, int from, CompositeTreeKey pattern, int to) {
		final int length = Math.max(bytes.getKey().length, pattern.getKey().length);
		for (int i = 0; i < length; i++) {
			final byte[] byteKey = i >= bytes.getKey().length ? null : bytes.getKey(i);
			final byte[] patternKey = i >= pattern.getKey().length ? null : pattern.getKey(i);

			if (byteKey != null && patternKey != null
					&& WireLikeMatcher.matches(
					WireLikeMatcher.utf8Payload(byteKey),
					WireLikeMatcher.utf8Payload(patternKey))) {
				return true;
			}
		}

		return false;
	}

	static class DefaultSplit implements LeafSplit<byte[][], CompositeTreeKey> {
		CompositeTreeKey compositeKey;
		Node<byte[][], CompositeTreeKey> right;

		DefaultSplit(CompositeTreeKey compositeKey, Node<byte[][], CompositeTreeKey> right) {
			this.compositeKey = compositeKey;
			this.right = right;
		}

		@Override
		public Node<byte[][], CompositeTreeKey> getRight() {
			return right;
		}

		@Override
		public CompositeTreeKey getSplitKey() {
			return compositeKey;
		}
	}

	static class DefaultInternalNode extends InternalNode<byte[][], CompositeTreeKey> {
		@Override
		@NonNull
		protected InternalNode<byte[][], CompositeTreeKey> createInternalNode() {
			return new DefaultInternalNode();
		}

		@Override
		@NonNull
		protected LeafSplit<byte[][], CompositeTreeKey> createLeafSplit(CompositeTreeKey splitKey, InternalNode<byte[][], CompositeTreeKey> right) {
			return new DefaultSplit(splitKey, right);
		}
	}

	static class DefaultLeafNode extends LeafNode<byte[][], CompositeTreeKey> {
		@Override
		@NonNull
		protected LeafNode<byte[][], CompositeTreeKey> createLeafNode() {
			return new DefaultLeafNode();
		}

		@Override
		@NonNull
		protected LeafSplit<byte[][], CompositeTreeKey> createLeafSplit(CompositeTreeKey splitKey, LeafNode<byte[][], CompositeTreeKey> right) {
			return new DefaultSplit(splitKey, right);
		}
	}
}
