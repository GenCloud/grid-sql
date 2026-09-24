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
package org.genfork.grid.mem;

import one.nio.util.Cleaner;
import org.genfork.grid.serial.SqlWireUtil;
import org.genfork.grid.utils.ArrayUtil;
import org.genfork.grid.utils.UnsafeMemory;
import org.springframework.lang.NonNull;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Off-heap sharded {@code byte[]}→{@code byte[]} working-set map (nodes × buckets).
 * <p>
 * Per-node {@link LifecycleRwGate} guards structural mutation only — never hold across
 * SQL / Netty / Reactor wait. {@link AbstractMap} façade retained for typed iterators;
 * product hot path uses {@link #put(byte[], byte[])} / {@link #get(Object)}.
 *
 * @author: GenCloud
 * @date: 2025/02
 * @since: 1.0
 */
public class GridScalableMap extends AbstractMap<byte[], byte[]> {
	private final UUID uuid = UUID.randomUUID();

	private static final float LOAD_FACTOR = 0.75f;
	private static final int DEFAULT_NODES = 16;
	private static final int INITIAL_CAPACITY = 16;

	private final AtomicReferenceArray<Node> nodes;

	public GridScalableMap() {
		nodes = new AtomicReferenceArray<>(DEFAULT_NODES);

		for (int i = 0; i < DEFAULT_NODES; i++) {
			nodes.set(i, new Node(INITIAL_CAPACITY));
		}

		new Cleaner(this) {
			@Override
			public void clear() {
				GridScalableMap.this.destroy();
			}
		};
	}

	private int getNodeIndex(byte[] key) {
		return Math.abs(ArrayUtil.fastHash(key) % nodes.length());
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		GridScalableMap that = (GridScalableMap) o;
		return Objects.equals(uuid, that.uuid);
	}

	public void destroy() {
		for (int idx = 0; idx < nodes.length(); idx++) {
			final Node node = nodes.get(idx);
			if (node != null) {
				node.internalClear();
			}
		}
	}

	@Override
	public int size() {
		int size = 0;

		for (int idx = 0; idx < nodes.length(); idx++) {
			final Node node = nodes.get(idx);
			if (node != null) {
				size += node.size();
			}
		}

		return size;
	}

	@Override
	public boolean containsKey(Object key) {
		if (key == null) {
			key = SqlWireUtil.getNullPtr();
		}

		if (!(key instanceof byte[] array)) {
			throw new UnsupportedOperationException();
		}

		return containsKey(array);
	}

	public boolean containsKey(byte[] key) {
		final int segmentIndex = getNodeIndex(key);
		return nodes.get(segmentIndex).containsKey(key);
	}

	@Override
	public boolean containsValue(Object value) {
		if (value == null) {
			value = SqlWireUtil.getNullPtr();
		}

		if (!(value instanceof byte[] array)) {
			throw new UnsupportedOperationException();
		}

		return containsValue(array);
	}

	public boolean containsValue(byte[] value) {
		for (int idx = 0; idx < nodes.length(); idx++) {
			final Node node = nodes.get(idx);
			if (node != null) {
				if (node.containsValue(value)) {
					return true;
				}
			}
		}

		return false;
	}

	@Override
	public byte[] put(byte[] key, byte[] value) {
		if (key == null) {
			key = SqlWireUtil.getNullPtr();
		}

		if (value == null) {
			value = SqlWireUtil.getNullPtr();
		}

		final int index = getNodeIndex(key);
		return nodes.get(index).put(key, value);
	}

	@Override
	public byte[] get(Object key) {
		if (key == null) {
			key = SqlWireUtil.getNullPtr();
		}

		if (!(key instanceof byte[] array)) {
			throw new UnsupportedOperationException();
		}

		return get(array);
	}

	public byte[] get(byte[] key) {
		final int index = getNodeIndex(key);
		return nodes.get(index).get(key);
	}

	@Override
	public byte[] remove(Object key) {
		if (key == null) {
			key = SqlWireUtil.getNullPtr();
		}

		if (!(key instanceof byte[] array)) {
			throw new UnsupportedOperationException();
		}

		return remove(array);
	}

	public byte[] remove(byte[] key) {
		final int index = getNodeIndex(key);
		return nodes.get(index).remove(key);
	}

	@Override
	@NonNull
	public Set<byte[]> keySet() {
		return new AbstractSet<>() {
			@Override
			@NonNull
			public Iterator<byte[]> iterator() {
				return new LazyIterator<>() {
					@Override
					Iterator<byte[]> getNodeIterator(Node node) {
						return node.keys();
					}
				};
			}

			@Override
			public int size() {
				return GridScalableMap.this.size();
			}
		};
	}

	@Override
	@NonNull
	public Collection<byte[]> values() {
		return new AbstractSet<>() {
			@Override
			@NonNull
			public Iterator<byte[]> iterator() {
				return new LazyIterator<>() {
					@Override
					Iterator<byte[]> getNodeIterator(Node node) {
						return node.values();
					}
				};
			}

			@Override
			public int size() {
				return GridScalableMap.this.size();
			}
		};
	}

	@Override
	@NonNull
	public Set<Entry<byte[], byte[]>> entrySet() {
		return new AbstractSet<>() {
			@Override
			@NonNull
			public Iterator<Entry<byte[], byte[]>> iterator() {
				return new LazyIterator<>() {
					@Override
					Iterator<Entry<byte[], byte[]>> getNodeIterator(Node node) {
						return node.entries();
					}
				};
			}

			@Override
			public int size() {
				return GridScalableMap.this.size();
			}
		};
	}

	abstract class LazyIterator<T> implements Iterator<T> {
		private int currentSegmentIndex = 0;
		private Iterator<T> currentSegmentIterator = getNextNodeIterator();

		abstract Iterator<T> getNodeIterator(Node node);

		private Iterator<T> getNextNodeIterator() {
			while (currentSegmentIndex < nodes.length()) {
				final Node node = nodes.get(currentSegmentIndex++);
				if (node != null && !node.isEmpty()) {
					return getNodeIterator(node);
				}
			}

			return null;
		}

		@Override
		public boolean hasNext() {
			while (currentSegmentIterator != null) {
				if (currentSegmentIterator.hasNext()) {
					return true;
				} else {
					currentSegmentIterator = getNextNodeIterator();
				}
			}
			return false;
		}

		@Override
		public T next() {
			if (hasNext()) {
				return currentSegmentIterator.next();
			}

			throw new NoSuchElementException();
		}
	}

	private static class Node {
		private final long headPtr;
		private final long capacity;
		private final LifecycleRwGate lifecycle;

		private final AtomicReference<Node> next = new AtomicReference<>();

		private final AtomicLong size = new AtomicLong();

		Node(long capacity) {
			this.capacity = capacity;

			final long initial = capacity * (UnsafeMemory.ADDRESS_SIZE * 2);
			headPtr = UnsafeMemory.malloc(initial);

			for (long i = 0; i < initial; i++) {
				UnsafeMemory.writeByte(headPtr + i, (byte) 0);
			}

			lifecycle = new LifecycleRwGate();
		}

		long getOffset(Node node, byte[] key) {
			return Math.abs(ArrayUtil.fastHash(key) % node.capacity);
		}

		Node getNode() {
			Node n = this;
			if (next.get() != null) {
				n = next.get();
			}

			return n;
		}

		int size() {
			final Node node = getNode();
			if (node.size.get() > Integer.MAX_VALUE) {
				return Integer.MAX_VALUE;
			}

			return (int) node.size.get();
		}

		boolean isEmpty() {
			final Node node = getNode();
			return node.size.get() == 0;
		}

		boolean containsKey(byte[] key) {
			if (key == null) {
				return false;
			}

			lifecycle.lockRead();
			try {
				final Node node = getNode();
				if (node.size.get() == 0) {
					return false;
				}

				final long keyOffset = getOffset(node, key);
				long locPtr = UnsafeMemory.getAddr(node.headPtr + (keyOffset * UnsafeMemory.ADDRESS_SIZE));
				if (locPtr == 0) {
					return false;
				}

				final int entryCount = UnsafeMemory.readInt(locPtr);
				locPtr += Integer.BYTES;

				for (long entryCursor = 0; entryCursor < entryCount; entryCursor++) {
					final Boolean exists = checkExistsOffset(locPtr, entryCursor, key);
					if (Boolean.TRUE.equals(exists)) {
						return true;
					}
				}

				return false;
			} finally {
				lifecycle.unlockRead();
			}
		}

		boolean containsValue(byte[] value) {
			if (value == null) {
				return false;
			}

			lifecycle.lockRead();
			try {
				final Node node = getNode();
				if (node.size.get() == 0) {
					return false;
				}

				for (long partition = 0; partition < node.capacity; partition++) {
					long locPtr = UnsafeMemory.getAddr(node.headPtr + (partition * UnsafeMemory.ADDRESS_SIZE));
					if (locPtr == 0) {
						continue;
					}

					final int entryCount = UnsafeMemory.readInt(locPtr);
					locPtr += Integer.BYTES;
					locPtr += UnsafeMemory.ADDRESS_SIZE;

					for (long entryCursor = 0; entryCursor < entryCount; entryCursor++) {
						final Boolean exists = checkExistsOffset(locPtr, entryCursor, value);
						if (Boolean.TRUE.equals(exists)) {
							return true;
						}
					}
				}
			} finally {
				lifecycle.unlockRead();
			}

			return false;
		}

		byte[] get(byte[] key) {
			if (key == null) {
				return null;
			}

			lifecycle.lockRead();
			try {
				final Node node = getNode();
				if (node.size.get() == 0) {
					return null;
				}

				final long keyOffset = getOffset(node, key);
				long locPtr = UnsafeMemory.getAddr(node.headPtr + (keyOffset * UnsafeMemory.ADDRESS_SIZE));
				if (locPtr == 0) {
					return null;
				}

				final int entryCount = UnsafeMemory.readInt(locPtr);
				locPtr += Integer.BYTES;

				for (long entryCursor = 0; entryCursor < entryCount; entryCursor++) {
					final Boolean exists = checkExistsOffset(locPtr, entryCursor, key);
					if (Boolean.TRUE.equals(exists)) {
						final long valuePtr = UnsafeMemory.getAddr(locPtr + (entryCursor * UnsafeMemory.ADDRESS_SIZE * 2) + UnsafeMemory.ADDRESS_SIZE);
						if (valuePtr == 0) {
							return null;
						}

						return readPtr(valuePtr);
					}
				}

				return null;
			} finally {
				lifecycle.unlockRead();
			}
		}

		byte[] put(byte[] key, byte[] value) {
			if (key == null) {
				return null;
			}

			lifecycle.lockWrite();
			try {
				final Node node = ensureCapacity();

				final int keyLength = key.length;
				final long keyOffset = getOffset(node, key);

				long locPtr = UnsafeMemory.getAddr(node.headPtr + (keyOffset * UnsafeMemory.ADDRESS_SIZE));

				final int entryCount = locPtr == 0 ? 0 : UnsafeMemory.readInt(locPtr);
				locPtr += Integer.BYTES;

				for (long entryCursor = 0; entryCursor < entryCount; entryCursor++) {
					final Boolean exists = checkExistsOffset(locPtr, entryCursor, key);
					if (Boolean.TRUE.equals(exists)) {
						long valuePtr = UnsafeMemory.getAddr(locPtr + (entryCursor * UnsafeMemory.ADDRESS_SIZE * 2) + UnsafeMemory.ADDRESS_SIZE);

						byte[] oldValue = null;
						if (valuePtr != 0) {
							oldValue = readPtr(valuePtr);
							UnsafeMemory.free(valuePtr);
						}

						if (value != null) {
							final int valueSize = value.length;

							valuePtr = UnsafeMemory.malloc(Integer.BYTES + valueSize);
							UnsafeMemory.writeInt(valuePtr, valueSize);
							UnsafeMemory.copy(value, UnsafeMemory.ARRAY_BASE_OFFSET, null, valuePtr + Integer.BYTES, valueSize);
						} else {
							valuePtr = 0;
						}

						UnsafeMemory.putAddr(locPtr + (entryCursor * UnsafeMemory.ADDRESS_SIZE * 2) + UnsafeMemory.ADDRESS_SIZE, valuePtr);

						return oldValue;
					}
				}

				node.size.incrementAndGet();

				locPtr -= Integer.BYTES;

				final long keyPtr = UnsafeMemory.malloc(Integer.BYTES + keyLength);
				UnsafeMemory.writeInt(keyPtr, keyLength);
				UnsafeMemory.copy(key, UnsafeMemory.ARRAY_BASE_OFFSET, null, keyPtr + Integer.BYTES, keyLength);

				long valuePtr = 0;

				if (value != null) {
					final int valueSize = value.length;
					valuePtr = UnsafeMemory.malloc(Integer.BYTES + valueSize);
					UnsafeMemory.writeInt(valuePtr, valueSize);
					UnsafeMemory.copy(value, UnsafeMemory.ARRAY_BASE_OFFSET, null, valuePtr + Integer.BYTES, valueSize);
				}

				if (locPtr == 0) {
					locPtr = UnsafeMemory.malloc(Integer.BYTES + UnsafeMemory.ADDRESS_SIZE + UnsafeMemory.ADDRESS_SIZE);
				} else {
					locPtr = UnsafeMemory.reallocate(locPtr, Integer.BYTES + (UnsafeMemory.ADDRESS_SIZE * 2 * (entryCount + 1)));
				}

				UnsafeMemory.putAddr(locPtr + Integer.BYTES + (UnsafeMemory.ADDRESS_SIZE * 2 * entryCount), keyPtr);
				UnsafeMemory.putAddr(locPtr + Integer.BYTES + (UnsafeMemory.ADDRESS_SIZE * 2 * entryCount) + UnsafeMemory.ADDRESS_SIZE, valuePtr);

				UnsafeMemory.putAddr(node.headPtr + (keyOffset * UnsafeMemory.ADDRESS_SIZE), locPtr);

				UnsafeMemory.writeInt(locPtr, entryCount + 1);
				return null;
			} finally {
				lifecycle.unlockWrite();
			}
		}

		public byte[] remove(byte[] key) {
			if (key == null) {
				return null;
			}

			lifecycle.lockWrite();
			try {
				final Node node = getNode();
				final int keySize = key.length;
				final long keyOffset = getOffset(node, key);

				long locPtr = UnsafeMemory.getAddr(node.headPtr + (keyOffset * UnsafeMemory.ADDRESS_SIZE));
				if (locPtr == 0) {
					return null;
				}

				final int entryCount = UnsafeMemory.readInt(locPtr);
				locPtr += Integer.BYTES;

				for (long entryCursor = 0; entryCursor < entryCount; entryCursor++) {
					long keyPtr = UnsafeMemory.getAddr(locPtr + (entryCursor * UnsafeMemory.ADDRESS_SIZE * 2));

					final int size = UnsafeMemory.readInt(keyPtr);
					if (size != keySize) {
						continue;
					}

					keyPtr += Integer.BYTES;

					boolean equal = true;
					for (int offset = 0; offset < keySize; offset++) {
						if (key[offset] != UnsafeMemory.readByte(keyPtr + offset)) {
							equal = false;
							break;
						}
					}

					if (equal) {
						keyPtr -= Integer.BYTES;
						UnsafeMemory.free(keyPtr);

						long valuePtr = UnsafeMemory.getAddr(locPtr + (entryCursor * UnsafeMemory.ADDRESS_SIZE * 2) + UnsafeMemory.ADDRESS_SIZE);

						byte[] removedValue = null;

						if (valuePtr != 0) {
							removedValue = readPtr(valuePtr);
							UnsafeMemory.free(valuePtr);
						}

						if (entryCursor < entryCount - 1) {
							long address = UnsafeMemory.getAddr(locPtr + ((long) (entryCount - 1) * UnsafeMemory.ADDRESS_SIZE * 2));
							UnsafeMemory.putAddr(locPtr + (entryCursor * UnsafeMemory.ADDRESS_SIZE * 2), address);

							address = UnsafeMemory.getAddr(locPtr + ((long) (entryCount - 1) * UnsafeMemory.ADDRESS_SIZE * 2) + UnsafeMemory.ADDRESS_SIZE);
							UnsafeMemory.putAddr(locPtr + (entryCursor * UnsafeMemory.ADDRESS_SIZE * 2) + UnsafeMemory.ADDRESS_SIZE, address);
						}

						locPtr -= Integer.BYTES;

						if (entryCount - 1 == 0) {
							UnsafeMemory.free(locPtr);
							UnsafeMemory.putAddr(node.headPtr + (keyOffset * UnsafeMemory.ADDRESS_SIZE), 0);
						} else {
							UnsafeMemory.writeInt(locPtr, entryCount - 1);

							locPtr = UnsafeMemory.reallocate(locPtr, Integer.BYTES + (UnsafeMemory.ADDRESS_SIZE * 2 * (entryCount - 1)));
							UnsafeMemory.putAddr(node.headPtr + (keyOffset * UnsafeMemory.ADDRESS_SIZE), locPtr);
						}

						node.size.decrementAndGet();
						return removedValue;
					}
				}

				return null;
			} finally {
				lifecycle.unlockWrite();
			}
		}

		void internalClear() {
			lifecycle.lockWrite();
			try {
				final Node node = getNode();
				clear(node);
			} finally {
				lifecycle.unlockWrite();
			}
		}

		void clear(Node node) {
			for (long offset = 0; offset < node.capacity; offset++) {
				long locPtr = UnsafeMemory.getAddr(node.headPtr + (offset * UnsafeMemory.ADDRESS_SIZE));
				if (locPtr == 0) {
					continue;
				}

				final int entryCount = UnsafeMemory.readInt(locPtr);
				locPtr += Integer.BYTES;

				for (long locationOffset = 0; locationOffset < entryCount; locationOffset++) {
					final long keyPtr = UnsafeMemory.getAddr(locPtr + (locationOffset * UnsafeMemory.ADDRESS_SIZE * 2));
					if (keyPtr != 0) {
						UnsafeMemory.free(keyPtr);
					}

					final long valuePtr = UnsafeMemory.getAddr(locPtr + (locationOffset * UnsafeMemory.ADDRESS_SIZE * 2) + UnsafeMemory.ADDRESS_SIZE);
					if (valuePtr != 0) {
						UnsafeMemory.free(valuePtr);
					}
				}

				locPtr -= Integer.BYTES;

				UnsafeMemory.free(locPtr);
				UnsafeMemory.putAddr(node.headPtr + (offset * UnsafeMemory.ADDRESS_SIZE), 0);
			}

			node.size.set(0);
		}

		Node ensureCapacity() {
			final Node node = getNode();

			if (node.size.get() >= node.capacity * LOAD_FACTOR) {
				final Node newNode = new Node(node.capacity * 2);

				final Iterator<Entry<byte[], byte[]>> iterator = entries();
				while (iterator.hasNext()) {
					final Entry<byte[], byte[]> entry = iterator.next();
					newNode.put(entry.getKey(), entry.getValue());
				}

				next.getAndUpdate(_ -> newNode);
				clear(node);

				return newNode;
			}

			return node;
		}

		Boolean checkExistsOffset(long locPtr, long locPtrCursor, byte[] data) {
			if (data == null) {
				return true;
			}

			int dataSize = data.length;

			long ptr = UnsafeMemory.getAddr(locPtr + (locPtrCursor * UnsafeMemory.ADDRESS_SIZE * 2));

			int ptrSize = UnsafeMemory.readInt(ptr);
			if (ptrSize != dataSize) {
				return null;
			}

			ptr += Integer.BYTES;

			boolean exists = true;
			for (int cursor = 0; cursor < dataSize; cursor++) {
				if (UnsafeMemory.readByte(ptr + cursor) != data[cursor]) {
					exists = false;
					break;
				}
			}

			return exists;
		}

		byte[] readPtr(long ptr) {
			final int dataSize = UnsafeMemory.readInt(ptr);
			final byte[] data = new byte[dataSize];
			ptr += Integer.BYTES;

			UnsafeMemory.copy(null, ptr, data, UnsafeMemory.ARRAY_BASE_OFFSET, dataSize);
			return data;
		}

		Iterator<byte[]> values() {
			final Node node = getNode();
			return new IteratorBase<>() {
				private long offset, entryCursor;

				@Override
				public boolean hasNext() {
					return hasNext(offset, node.capacity, node.headPtr);
				}

				@Override
				public byte[] next() {
					if (offset >= node.capacity) {
						throw new NoSuchElementException();
					}

					long locPtr = UnsafeMemory.getAddr(node.headPtr + (offset * UnsafeMemory.ADDRESS_SIZE));

					while (locPtr == 0) {
						offset++;

						if (offset >= node.capacity) {
							throw new NoSuchElementException();
						}

						locPtr = UnsafeMemory.getAddr(node.headPtr + (offset * UnsafeMemory.ADDRESS_SIZE));
					}

					final int entryCount = UnsafeMemory.readInt(locPtr);
					locPtr += Integer.BYTES;

					long valuePtr = UnsafeMemory.getAddr(locPtr + (entryCursor * UnsafeMemory.ADDRESS_SIZE * 2) + UnsafeMemory.ADDRESS_SIZE);

					entryCursor++;
					if (entryCursor >= entryCount) {
						entryCursor = 0;
						offset++;
					}

					if (valuePtr == 0) {
						return null;
					}

					return readPtr(valuePtr);
				}
			};
		}

		Iterator<byte[]> keys() {
			final Node node = getNode();
			return new IteratorBase<>() {
				private long offset, entryCursor;

				@Override
				public boolean hasNext() {
					return hasNext(offset, node.capacity, node.headPtr);
				}

				@Override
				public byte[] next() {
					if (offset >= node.capacity) {
						throw new NoSuchElementException();
					}

					long locPtr = UnsafeMemory.getAddr(node.headPtr + (offset * UnsafeMemory.ADDRESS_SIZE));

					while (locPtr == 0) {
						offset++;

						if (offset >= node.capacity) {
							throw new NoSuchElementException();
						}

						locPtr = UnsafeMemory.getAddr(node.headPtr + (offset * UnsafeMemory.ADDRESS_SIZE));
					}

					final int entryCount = UnsafeMemory.readInt(locPtr);
					locPtr += Integer.BYTES;

					long keyPtr = UnsafeMemory.getAddr(locPtr + (entryCursor * UnsafeMemory.ADDRESS_SIZE * 2));

					entryCursor++;
					if (entryCursor >= entryCount) {
						entryCursor = 0;
						offset++;
					}

					return readPtr(keyPtr);
				}
			};
		}

		Iterator<Entry<byte[], byte[]>> entries() {
			final Node node = getNode();
			return new IteratorBase<>() {
				private long offset, entryCursor;

				@Override
				public boolean hasNext() {
					return hasNext(offset, node.capacity, node.headPtr);
				}

				@Override
				public Entry<byte[], byte[]> next() {
					if (offset >= node.capacity) {
						throw new NoSuchElementException();
					}

					long locPtr = UnsafeMemory.getAddr(node.headPtr + (offset * UnsafeMemory.ADDRESS_SIZE));

					while (locPtr == 0) {
						offset++;

						if (offset >= node.capacity) {
							throw new NoSuchElementException();
						}

						locPtr = UnsafeMemory.getAddr(node.headPtr + (offset * UnsafeMemory.ADDRESS_SIZE));
					}

					final int entryCount = UnsafeMemory.readInt(locPtr);
					locPtr += Integer.BYTES;

					long keyPtr = UnsafeMemory.getAddr(locPtr + (entryCursor * UnsafeMemory.ADDRESS_SIZE * 2));
					long valuePtr = UnsafeMemory.getAddr(locPtr + (entryCursor * UnsafeMemory.ADDRESS_SIZE * 2) + UnsafeMemory.ADDRESS_SIZE);

					entryCursor++;
					if (entryCursor >= entryCount) {
						entryCursor = 0;
						offset++;
					}

					final byte[] keyData = readPtr(keyPtr);
					if (valuePtr == 0) {
						return new SimpleEntry<>(keyData, null);
					}

					final byte[] valueData = readPtr(valuePtr);
					return new SimpleEntry<>(keyData, valueData);
				}
			};
		}

		interface IteratorBase<O> extends Iterator<O> {
			default boolean hasNext(long offset, long capacity, long ptr) {
				while (offset < capacity) {
					final long locPtr = UnsafeMemory.getAddr(ptr + (offset * UnsafeMemory.ADDRESS_SIZE));

					if (locPtr == 0) {
						offset++;
						continue;
					}

					break;
				}

				return offset < capacity;
			}
		}
	}
}
