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
import org.genfork.grid.replication.util.SealedShardInventoryEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Characterization: local sealed inventory memory index.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
class LocalSealedInventoryIndexTest {

	private static final String DOMAIN = "castle";
	private static final int SHARD = 0;
	private static final long FP = 42L;

	@Test
	void emptyIndexReturnsZero() {
		assertEquals(0L, LocalSealedInventoryIndex.empty().fingerprint(DOMAIN, SHARD));
		assertEquals(0, LocalSealedInventoryIndex.empty().size());
	}

	@Test
	void fromEntriesIndexesNonZeroFp() {
		final ArrayList<SealedShardInventoryEntry> entries = new ArrayList<>();
		entries.add(new SealedShardInventoryEntry(DOMAIN, SHARD, FP));
		entries.add(new SealedShardInventoryEntry(DOMAIN, 1, 0L));
		entries.add(null);
		final LocalSealedInventoryIndex index = LocalSealedInventoryIndex.fromEntries(entries);
		assertEquals(FP, index.fingerprint(DOMAIN, SHARD));
		assertEquals(0L, index.fingerprint(DOMAIN, 1));
		assertEquals(1, index.size());
	}

	@Test
	void absentKeyIsZeroNotUnknown() {
		final LocalSealedInventoryIndex index = LocalSealedInventoryIndex.fromEntries(List.of(
				new SealedShardInventoryEntry(DOMAIN, SHARD, FP)));
		assertEquals(0L, index.fingerprint("other", 0));
	}
}
