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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Per {@code domain#shard} sealed reader lifecycle: exclusive mutate vs pinned iterate.
 * <p>
 * Exclusive sections (open / swap / release / unpack) wait until {@code pins == 0}.
 * FULL hydrate pins across {@code forEachLive} so HELLO pack cannot {@code close} mid-iterate,
 * without holding the mutex across the iterate body (no hydrate/TPS stall under pack traffic).
 * Never hold across Netty EL wait or SQL execute.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
public final class SealedReaderLifecycleGate {
	private static final char KEY_SEP = '#';

	private final ConcurrentHashMap<String, ShardGate> gates = new ConcurrentHashMap<>();

	/**
	 * Run exclusive work (open / swap / release / unpack). Blocks while any pin is held.
	 */
	public void runExclusive(String domainType, int shard, Runnable action) {
		final ShardGate gate = gateFor(domainType, shard);
		gate.lock.lock();
		try {
			awaitNoPins(gate);
			action.run();
		} finally {
			gate.lock.unlock();
		}
	}

	/**
	 * Exclusive call (open / swap / unpack). Blocks while any pin is held.
	 */
	public <T> T callExclusive(String domainType, int shard, Supplier<T> action) {
		final ShardGate gate = gateFor(domainType, shard);
		gate.lock.lock();
		try {
			awaitNoPins(gate);
			return action.get();
		} finally {
			gate.lock.unlock();
		}
	}

	/**
	 * Exclusive open; if {@code openAction} returns {@code true}, leaves one pin held for iterate
	 * outside the lock. Caller <strong>must</strong> {@link #unpin} in {@code finally} when true.
	 *
	 * @return whether a pin was taken
	 */
	public boolean runExclusiveAndPin(String domainType, int shard, BooleanSupplier openAction) {
		final ShardGate gate = gateFor(domainType, shard);
		gate.lock.lock();
		try {
			awaitNoPins(gate);
			final boolean pinned = openAction.getAsBoolean();
			if (pinned) {
				gate.pins++;
			}
			return pinned;
		} finally {
			gate.lock.unlock();
		}
	}

	/**
	 * Pin an already-open reader for iterate outside exclusive (dump merge {@code forEachLive}).
	 * Caller <strong>must</strong> {@link #unpin} in {@code finally}. Does not open/swap.
	 */
	public void pin(String domainType, int shard) {
		final ShardGate gate = gateFor(domainType, shard);
		gate.lock.lock();
		try {
			gate.pins++;
		} finally {
			gate.lock.unlock();
		}
	}

	/**
	 * Drop one pin acquired by {@link #runExclusiveAndPin} or {@link #pin}; wakes exclusive waiters at zero.
	 */
	public void unpin(String domainType, int shard) {
		final ShardGate gate = gateFor(domainType, shard);
		gate.lock.lock();
		try {
			if (gate.pins <= 0) {
				return;
			}
			gate.pins--;
			if (gate.pins == 0) {
				gate.idle.signalAll();
			}
		} finally {
			gate.lock.unlock();
		}
	}

	private static void awaitNoPins(ShardGate gate) {
		while (gate.pins > 0) {
			try {
				gate.idle.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("interrupted waiting sealed reader pin drain", e);
			}
		}
	}

	private ShardGate gateFor(String domainType, int shard) {
		return gates.computeIfAbsent(key(domainType, shard), ignored -> new ShardGate());
	}

	static String key(String domainType, int shard) {
		return domainType + KEY_SEP + shard;
	}

	private static final class ShardGate {
		private final ReentrantLock lock = new ReentrantLock();
		private final Condition idle = lock.newCondition();
		private int pins;
	}
}
