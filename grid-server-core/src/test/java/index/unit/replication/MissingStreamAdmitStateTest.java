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

import org.genfork.grid.replication.util.LocalSealedInventoryIndex;
import org.genfork.grid.replication.util.MissingStreamAdmitState;
import org.genfork.grid.replication.util.OpLogStreamKeyUtil;
import org.genfork.grid.replication.util.SealedShardInventoryEntry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterization: sticky missing-stream admit state (fail-closed until ready).
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
class MissingStreamAdmitStateTest {

	private static final String DOMAIN = "castle";
	private static final int SHARD = 0;

	@Test
	void notReadyIsFailClosed() {
		final MissingStreamAdmitState state = new MissingStreamAdmitState();
		assertFalse(state.isInventoryReady());
		assertTrue(state.isIncomplete());
		state.markNotReady();
		assertTrue(state.isIncomplete());
	}

	@Test
	void publishWithLocalFpClearsIncompleteForFollower() {
		final MissingStreamAdmitState state = new MissingStreamAdmitState();
		final Map<String, Long> proposer = new LinkedHashMap<>();
		proposer.put(OpLogStreamKeyUtil.format(DOMAIN, SHARD), 99L);
		final LocalSealedInventoryIndex index = LocalSealedInventoryIndex.fromEntries(List.of(
				new SealedShardInventoryEntry(DOMAIN, SHARD, 99L)));
		assertFalse(state.publishAndRecompute(
				index,
				true,
				proposer,
				DOMAIN::equals,
				(d, s) -> 0L,
				(d, s) -> 0L));
		assertTrue(state.isInventoryReady());
		assertFalse(state.isIncomplete());
	}

	@Test
	void publishWithoutLocalFpMarksIncomplete() {
		final MissingStreamAdmitState state = new MissingStreamAdmitState();
		final Map<String, Long> proposer = new LinkedHashMap<>();
		proposer.put(OpLogStreamKeyUtil.format(DOMAIN, SHARD), 99L);
		assertTrue(state.publishAndRecompute(
				LocalSealedInventoryIndex.empty(),
				true,
				proposer,
				DOMAIN::equals,
				(d, s) -> 0L,
				(d, s) -> 0L));
		assertTrue(state.isIncomplete());
	}

	@Test
	void writerRecomputeClearsSticky() {
		final MissingStreamAdmitState state = new MissingStreamAdmitState();
		final Map<String, Long> proposer = new LinkedHashMap<>();
		proposer.put(OpLogStreamKeyUtil.format(DOMAIN, SHARD), 99L);
		state.publishAndRecompute(
				LocalSealedInventoryIndex.empty(),
				true,
				proposer,
				DOMAIN::equals,
				(d, s) -> 0L,
				(d, s) -> 0L);
		assertTrue(state.isIncomplete());
		assertFalse(state.recompute(
				false,
				proposer,
				DOMAIN::equals,
				(d, s) -> 0L,
				(d, s) -> 0L));
		assertFalse(state.isIncomplete());
	}

	@Test
	void markNotReadyAfterPublishFailsClosedAgain() {
		final MissingStreamAdmitState state = new MissingStreamAdmitState();
		state.publishAndRecompute(
				LocalSealedInventoryIndex.empty(),
				false,
				Map.of(),
				d -> true,
				(d, s) -> 0L,
				(d, s) -> 0L);
		assertFalse(state.isIncomplete());
		state.markNotReady();
		assertTrue(state.isIncomplete());
		assertFalse(state.isInventoryReady());
	}
}
