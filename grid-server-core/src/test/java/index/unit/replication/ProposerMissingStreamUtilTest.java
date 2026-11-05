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
package index.unit.replication;

import org.genfork.grid.replication.util.OpLogStreamKeyUtil;
import org.genfork.grid.replication.util.ProposerMissingStreamUtil;
import org.genfork.grid.replication.util.SealedShardInventoryEntry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization: proposer missing-stream incompleteness predicates.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
class ProposerMissingStreamUtilTest {

	private static final String DOMAIN = "castle";
	private static final int SHARD = 0;

	@Test
	void missingWhenPeerFpNonZeroAndLocalEmpty() {
		assertTrue(ProposerMissingStreamUtil.isMissingStreamShard(
				DOMAIN, SHARD, 99L,
				(d, s) -> 0L,
				(d, s) -> 0L,
				(d, s) -> 0L));
	}

	@Test
	void notMissingWhenLocalOpLogHasTip() {
		assertFalse(ProposerMissingStreamUtil.isMissingStreamShard(
				DOMAIN, SHARD, 99L,
				(d, s) -> 5L,
				(d, s) -> 5L,
				(d, s) -> 0L));
	}

	@Test
	void notMissingWhenBothFpZeroPeer() {
		assertFalse(ProposerMissingStreamUtil.isMissingStreamShard(
				DOMAIN, SHARD, 0L,
				(d, s) -> 0L,
				(d, s) -> 0L,
				(d, s) -> 0L));
	}

	@Test
	void notMissingWhenLocalSealedPresent() {
		assertFalse(ProposerMissingStreamUtil.isMissingStreamShard(
				DOMAIN, SHARD, 99L,
				(d, s) -> 0L,
				(d, s) -> 0L,
				(d, s) -> 42L));
	}

	@Test
	void hasMissingStreamRequiresFollowerAndKnownDomain() {
		final Map<String, Long> proposer = new LinkedHashMap<>();
		proposer.put(OpLogStreamKeyUtil.format(DOMAIN, SHARD), 77L);
		assertFalse(ProposerMissingStreamUtil.hasMissingStream(
				false, proposer, d -> true,
				(d, s) -> 0L, (d, s) -> 0L, (d, s) -> 0L));
		assertFalse(ProposerMissingStreamUtil.hasMissingStream(
				true, proposer, d -> false,
				(d, s) -> 0L, (d, s) -> 0L, (d, s) -> 0L));
		assertTrue(ProposerMissingStreamUtil.hasMissingStream(
				true, proposer, DOMAIN::equals,
				(d, s) -> 0L, (d, s) -> 0L, (d, s) -> 0L));
	}

	@Test
	void differentialObserveWhenLocalFpDiffers() {
		final AtomicLong localFp = new AtomicLong(1L);
		assertTrue(ProposerMissingStreamUtil.needsDifferentialObserve(
				List.of(new SealedShardInventoryEntry(DOMAIN, SHARD, 2L)),
				(d, s) -> localFp.get()));
		assertFalse(ProposerMissingStreamUtil.needsDifferentialObserve(
				List.of(new SealedShardInventoryEntry(DOMAIN, SHARD, 1L)),
				(d, s) -> localFp.get()));
	}
}
