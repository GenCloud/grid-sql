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
package org.genfork.grid.replication.util;


import java.util.Map;
import java.util.function.Predicate;
import java.util.function.ToLongBiFunction;

/**
 * Follower READ_REPLICA incompleteness: proposer sealed inventory advertises a shard
 * the local node has neither OpLog activity nor sealed identity for.
 * <p>
 * Does <em>not</em> fence layout drift (both fingerprints non-zero) or seal-dump lag
 * when local {@code applied >= lastSeq} with {@code lastSeq > 0}.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
public final class ProposerMissingStreamUtil {

	private ProposerMissingStreamUtil() {
	}

	/**
	 * {@code true} when any proposer inventory entry is a missing-stream hole on this follower.
	 *
	 * @param follower                 local node is not writer-eligible
	 * @param proposerShardFpByStream  streamKey → identity FP from phase-ranked proposer HELLO
	 * @param domainKnownLocally       domain registered for SQL/apply (dropped-table filter)
	 * @param lastSeq                  OpLog tip for domain#shard (0 if absent)
	 * @param appliedWatermark         applied watermark for domain#shard
	 * @param localSealedFp            local sealed identity FP (0 if missing)
	 */
	public static boolean hasMissingStream(
			boolean follower,
			Map<String, Long> proposerShardFpByStream,
			Predicate<String> domainKnownLocally,
			ToLongBiFunction<String, Integer> lastSeq,
			ToLongBiFunction<String, Integer> appliedWatermark,
			ToLongBiFunction<String, Integer> localSealedFp
	) {
		if (!follower
				|| proposerShardFpByStream == null
				|| proposerShardFpByStream.isEmpty()
				|| domainKnownLocally == null
				|| lastSeq == null
				|| appliedWatermark == null
				|| localSealedFp == null) {
			return false;
		}
		for (Map.Entry<String, Long> entry : proposerShardFpByStream.entrySet()) {
			if (entry == null || entry.getKey() == null || entry.getValue() == null) {
				continue;
			}
			final long peerFp = entry.getValue();
			if (peerFp == 0L) {
				continue;
			}
			final OpLogStreamKeyUtil.StreamKey parsed = OpLogStreamKeyUtil.parse(entry.getKey());
			if (parsed == null || parsed.domain() == null) {
				continue;
			}
			if (!domainKnownLocally.test(parsed.domain())) {
				continue;
			}
			if (isMissingStreamShard(
					parsed.domain(),
					parsed.shard(),
					peerFp,
					lastSeq,
					appliedWatermark,
					localSealedFp)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Single-shard predicate (unit-testable).
	 */
	public static boolean isMissingStreamShard(
			String domain,
			int shard,
			long peerFp,
			ToLongBiFunction<String, Integer> lastSeq,
			ToLongBiFunction<String, Integer> appliedWatermark,
			ToLongBiFunction<String, Integer> localSealedFp
	) {
		if (domain == null || peerFp == 0L || lastSeq == null || appliedWatermark == null || localSealedFp == null) {
			return false;
		}
		final long tip = lastSeq.applyAsLong(domain, shard);
		final long applied = appliedWatermark.applyAsLong(domain, shard);
		if (tip > 0L) {
			// Local OpLog has history — seal dump lag alone is not incompleteness.
			return false;
		}
		if (applied > 0L) {
			return false;
		}
		final long localFp = localSealedFp.applyAsLong(domain, shard);
		return localFp == 0L;
	}

	/**
	 * {@code true} when HELLO inventory needs differential disk observe (any local FP ≠ peer FP).
	 */
	public static boolean needsDifferentialObserve(
			Iterable<SealedShardInventoryEntry> peerInventory,
			ToLongBiFunction<String, Integer> localSealedFp
	) {
		if (peerInventory == null || localSealedFp == null) {
			return false;
		}
		for (SealedShardInventoryEntry entry : peerInventory) {
			if (entry == null || entry.domain() == null || entry.identityFingerprint() == 0L) {
				continue;
			}
			final long localFp = localSealedFp.applyAsLong(entry.domain(), entry.shard());
			if (localFp != entry.identityFingerprint()) {
				return true;
			}
		}
		return false;
	}
}
