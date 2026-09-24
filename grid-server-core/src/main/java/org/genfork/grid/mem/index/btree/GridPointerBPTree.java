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

import org.springframework.lang.NonNull;

import org.genfork.grid.serial.WireLikeMatcher;

/**
 * @author: GenCloud
 * @date: 2025/04
 * @since: 1.0
 */
public class GridPointerBPTree extends AbstractBPTree<byte[], SingleTreeKey> {
	public GridPointerBPTree(String indexName, boolean strict) {
		super(indexName, strict);
	}

	@Override
	public SingleTreeKey createKey(String indexedProperty, byte[] value) {
		return new SingleTreeKey(value);
	}

	@Override
	public SingleTreeKey createKey(byte[][] value) {
		throw new UnsupportedOperationException();
	}

	@Override
	protected int getKeyMemorySize(SingleTreeKey key) {
		return Long.BYTES * 2 + key.getKey().length;
	}

	@Override
	@NonNull
	protected InternalNode<byte[], SingleTreeKey> createInternalNode() {
		return new DefaultInternalNode();
	}

	@Override
	@NonNull
	protected LeafNode<byte[], SingleTreeKey> createLeafNode() {
		return new DefaultLeafNode();
	}

	@Override
	protected boolean matchesPattern(SingleTreeKey bytes, int from, SingleTreeKey pattern, int to) {
		return WireLikeMatcher.matches(
				WireLikeMatcher.utf8Payload(bytes.getKey()),
				WireLikeMatcher.utf8Payload(pattern.getKey()));
	}

	static class DefaultSplit implements LeafSplit<byte[], SingleTreeKey> {
		SingleTreeKey key;
		Node<byte[], SingleTreeKey> right;

		DefaultSplit(SingleTreeKey key, Node<byte[], SingleTreeKey> right) {
			this.key = key;
			this.right = right;
		}

		@Override
		public Node<byte[], SingleTreeKey> getRight() {
			return right;
		}

		@Override
		public SingleTreeKey getSplitKey() {
			return key;
		}
	}

	static class DefaultInternalNode extends InternalNode<byte[], SingleTreeKey> {
		@Override
		@NonNull
		protected InternalNode<byte[], SingleTreeKey> createInternalNode() {
			return new DefaultInternalNode();
		}

		@Override
		@NonNull
		protected LeafSplit<byte[], SingleTreeKey> createLeafSplit(SingleTreeKey splitKey, InternalNode<byte[], SingleTreeKey> right) {
			return new DefaultSplit(splitKey, right);
		}
	}

	public static class DefaultLeafNode extends LeafNode<byte[], SingleTreeKey> {
		@Override
		@NonNull
		protected LeafNode<byte[], SingleTreeKey> createLeafNode() {
			return new DefaultLeafNode();
		}

		@Override
		@NonNull
		protected LeafSplit<byte[], SingleTreeKey> createLeafSplit(SingleTreeKey splitKey, LeafNode<byte[], SingleTreeKey> right) {
			return new DefaultSplit(splitKey, right);
		}
	}
}
