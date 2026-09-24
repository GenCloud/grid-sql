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

import org.genfork.grid.fs.GridFs;
import org.genfork.grid.mem.GridScalableMap;
import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapReader;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Incomplete seal under WS eviction: dumpDomain must merge sealed and RAM before truncate.
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
@DisplayName("EvictionSealTruncate")
class EvictionSealTruncateTest {
	private static final String DOMAIN = "evict_seal";
	private static final int SHARD_ZERO = 0;
	private static final byte[] KEY_SEALED = "sealed-only".getBytes(StandardCharsets.UTF_8);
	private static final byte[] KEY_RAM = "ram-only".getBytes(StandardCharsets.UTF_8);
	private static final byte[] KEY_BOTH = "both".getBytes(StandardCharsets.UTF_8);
	private static final byte[] VAL_A = "a".getBytes(StandardCharsets.UTF_8);
	private static final byte[] VAL_B = "b".getBytes(StandardCharsets.UTF_8);
	private static final byte[] VAL_C = "c".getBytes(StandardCharsets.UTF_8);
	private static final byte[] VAL_C2 = "c2".getBytes(StandardCharsets.UTF_8);

	@TempDir
	Path tmp;

	@Test
	@DisplayName("dumpDomain_mergesPriorSealedWithRam_afterEvict")
	void dumpDomain_mergesPriorSealedWithRam_afterEvict() throws Exception {
		final Path sealedRoot = tmp.resolve("sealed");
		GridFs.createDirs(sealedRoot);
		final SealedGridMapService service = new SealedGridMapService(sealedRoot);
		final GridEntriesProcessor processor = new GridEntriesProcessor(
				SHARD_ZERO, new GridScalableMap(), null, null);
		service.bindProcessor(DOMAIN, shard -> shard == SHARD_ZERO ? processor : null);

		processor.installCommitted(KEY_SEALED, VAL_A, false);
		processor.installCommitted(KEY_BOTH, VAL_C, false);
		assertTrue(service.dumpDomain(DOMAIN, null, null) >= 2);

		processor.evictCommitted(KEY_SEALED);
		final boolean[] stillInRam = {false};
		processor.forEachCommitted((key, value) -> {
			if (Arrays.equals(key, KEY_SEALED)) {
				stillInRam[0] = true;
			}
		});
		assertTrue(!stillInRam[0], "KEY_SEALED must leave RAM map after evict");
		processor.installCommitted(KEY_RAM, VAL_B, false);
		processor.installCommitted(KEY_BOTH, VAL_C2, false);

		final int rows = service.dumpDomain(DOMAIN, null, null);
		assertEquals(3, rows, "sealed+RAM must retain evicted key + RAM keys");

		final SealedGridMapReader after = service.reader(DOMAIN, SHARD_ZERO);
		assertNotNull(after);
		assertArrayEquals(VAL_A, after.get(KEY_SEALED), "evicted key lost without sealed merge");
		assertArrayEquals(VAL_B, after.get(KEY_RAM));
		assertArrayEquals(VAL_C2, after.get(KEY_BOTH), "RAM must win over prior sealed");
		service.closeAll();
	}
}