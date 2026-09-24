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
package org.genfork.grid.replication.swarm;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-stream post-cutover cooldown: suppress re-emit of the same {@code domain#shard}
 * migrate for a fixed number of swarm ticks after successful ownership cutover.
 *
 * @author: GenCloud
 * @date: 2026/06
 * @since: 1.0
 */
public final class ShardMigrateCooldown {
	public static final int DEFAULT_CUTOVER_COOLDOWN_TICKS = 12;

	private final int cooldownTicks;
	private final ConcurrentHashMap<String, AtomicInteger> remainingByStream = new ConcurrentHashMap<>();

	public ShardMigrateCooldown() {
		this(DEFAULT_CUTOVER_COOLDOWN_TICKS);
	}

	public ShardMigrateCooldown(int cooldownTicks) {
		this.cooldownTicks = Math.max(0, cooldownTicks);
	}

	public int cooldownTicks() {
		return cooldownTicks;
	}

	/**
	 * Record a successful cutover so subsequent ticks suppress the same stream.
	 */
	public void onCutover(String domainType, int shard) {
		if (cooldownTicks <= 0 || domainType == null) {
			return;
		}
		remainingByStream.put(streamKey(domainType, shard), new AtomicInteger(cooldownTicks));
	}

	/**
	 * {@code true} when this stream is still within post-cutover cooldown.
	 */
	public boolean isCooling(String domainType, int shard) {
		if (cooldownTicks <= 0 || domainType == null) {
			return false;
		}
		final AtomicInteger remaining = remainingByStream.get(streamKey(domainType, shard));
		return remaining != null && remaining.get() > 0;
	}

	/**
	 * Decrement all active cooldowns by one tick (call once per {@code swarmTick}).
	 */
	public void tick() {
		if (cooldownTicks <= 0 || remainingByStream.isEmpty()) {
			return;
		}
		remainingByStream.forEach((key, counter) -> {
			if (counter.decrementAndGet() <= 0) {
				remainingByStream.remove(key, counter);
			}
		});
	}

	private static String streamKey(String domainType, int shard) {
		return Objects.requireNonNull(domainType, "domainType") + "#" + shard;
	}
}
