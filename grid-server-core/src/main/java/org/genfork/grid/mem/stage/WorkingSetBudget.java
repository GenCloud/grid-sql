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
package org.genfork.grid.mem.stage;

import org.genfork.grid.utils.ArrayUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * CLOCK working-set budget across shard processors. Evicts cold committed keys only
 * (caller must skip dirty/staging). No monitor / synchronized — VT-safe.
 * <p>
 * Cap is mutable for adaptive disk-first tighten/relax ({@link #setMaxEntries(int)}).
 *
 * @author: GenCloud
 * @date: 2026/02
 * @since: 1.0
 */
public final class WorkingSetBudget {
	private final AtomicInteger maxEntries;
	private final ConcurrentHashMap<KeyRef, Meta> entries;
	private final AtomicInteger size = new AtomicInteger();
	private final AtomicBoolean preferSealedOnly = new AtomicBoolean(false);
	private volatile BiConsumer<Integer, byte[]> evictHandler;

	public WorkingSetBudget(int maxEntries) {
		this.maxEntries = new AtomicInteger(Math.max(0, maxEntries));
		this.entries = new ConcurrentHashMap<>(16);
	}

	public int maxEntries() {
		return maxEntries.get();
	}

	/**
	 * Adaptive tighten/relax. {@code 0} = unlimited (no CLOCK drain).
	 * Tightening drains overflow via evict handler outside any monitor.
	 */
	public void setMaxEntries(int newMax) {
		final int capped = Math.max(0, newMax);
		maxEntries.set(capped);
		if (capped > 0 && size.get() > capped) {
			drainOverflow();
		}
	}

	/** When true, sealed-miss path should not warm RAM (disk-first reads under HIGH). */
	public void setPreferSealedOnly(boolean preferSealedOnly) {
		this.preferSealedOnly.set(preferSealedOnly);
	}

	public boolean preferSealedOnly() {
		return preferSealedOnly.get();
	}

	public void setEvictHandler(BiConsumer<Integer, byte[]> evictHandler) {
		this.evictHandler = evictHandler;
	}

	public void touch(int shard, byte[] key) {
		final int cap = maxEntries.get();
		if (cap <= 0 || key == null) {
			return;
		}
		final KeyRef ref = new KeyRef(key);
		final Meta existing = entries.get(ref);
		if (existing != null) {
			existing.referenced.set(true);
			return;
		}
		final Meta created = new Meta(shard);
		final Meta raced = entries.putIfAbsent(ref, created);
		if (raced != null) {
			raced.referenced.set(true);
			return;
		}
		final int n = size.incrementAndGet();
		if (n > cap) {
			drainOverflow();
		}
	}

	public void remove(byte[] key) {
		if (maxEntries.get() <= 0 || key == null) {
			return;
		}
		final Meta removed = entries.remove(new KeyRef(key));
		if (removed != null) {
			size.decrementAndGet();
		}
	}

	private void drainOverflow() {
		final int cap = maxEntries.get();
		if (cap <= 0) {
			return;
		}
		final BiConsumer<Integer, byte[]> handler = evictHandler;
		if (handler == null) {
			return;
		}
		final List<Victim> victims = new ArrayList<>();
		int guard = 0;
		while (size.get() > cap && guard++ < cap * 4 + 16) {
			boolean progressed = false;
			for (Map.Entry<KeyRef, Meta> e : entries.entrySet()) {
				if (size.get() <= cap) {
					break;
				}
				final Meta meta = e.getValue();
				if (meta.referenced.compareAndSet(true, false)) {
					continue;
				}
				if (entries.remove(e.getKey(), meta)) {
					size.decrementAndGet();
					victims.add(new Victim(meta.shard, e.getKey().key));
					progressed = true;
				}
			}
			if (!progressed) {
				break;
			}
		}
		for (Victim v : victims) {
			handler.accept(v.shard, v.key);
		}
	}

	public int size() {
		return size.get();
	}

	private static final class Meta {
		private final int shard;
		private final AtomicBoolean referenced = new AtomicBoolean(true);

		private Meta(int shard) {
			this.shard = shard;
		}
	}

	private static final class Victim {
		private final int shard;
		private final byte[] key;

		private Victim(int shard, byte[] key) {
			this.shard = shard;
			this.key = key;
		}
	}

	private static final class KeyRef {
		private final byte[] key;
		private final int hash;

		private KeyRef(byte[] key) {
			this.key = key;
			this.hash = ArrayUtil.fastHash(key);
		}

		@Override
		public boolean equals(Object o) {
			if (o == this) {
				return true;
			}
			if (!(o instanceof KeyRef other)) {
				return false;
			}
			return Arrays.equals(key, other.key);
		}

		@Override
		public int hashCode() {
			return hash;
		}
	}
}
