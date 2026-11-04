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

import org.genfork.grid.replication.snapshot.sealed.SealedBPTreeService;
import org.genfork.grid.replication.snapshot.sealed.SealedBitmapService;
import org.genfork.grid.replication.snapshot.sealed.SealedIndexPathUtil;
import org.genfork.grid.replication.snapshot.sealed.SealedShardPack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SealedIndexPathUtil} keeps {@code .sbpt} and {@code .sbm} paths aligned; {@code +} retained for composites.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
class SealedIndexPathUtilTest {
	private static final String DOMAIN = "public.orders";
	private static final int SHARD = 2;
	private static final String COMPOSITE = "col1+col2";

	@TempDir
	Path tempDir;

	@Test
	void compositeNameSameStemForSbptAndSbm() {
		final Path sealedRoot = tempDir.resolve("sealed");
		final SealedBPTreeService bptree = new SealedBPTreeService(sealedRoot);
		final SealedBitmapService bitmap = new SealedBitmapService(sealedRoot);

		final Path sbpt = bptree.indexFile(DOMAIN, SHARD, COMPOSITE);
		final Path sbm = bitmap.indexFile(DOMAIN, SHARD, COMPOSITE);
		final Path utilSbpt = SealedIndexPathUtil.indexFile(sealedRoot, DOMAIN, SHARD, COMPOSITE, ".sbpt");
		final Path utilSbm = SealedIndexPathUtil.indexFile(sealedRoot, DOMAIN, SHARD, COMPOSITE, ".sbm");

		assertEquals(sbpt, utilSbpt);
		assertEquals(sbm, utilSbm);

		final String hex = SealedShardPack.domainHex(DOMAIN);
		final String expectedStem = hex + "_" + SHARD + "_idx_col1+col2";
		assertTrue(sbpt.getFileName().toString().startsWith(expectedStem));
		assertTrue(sbm.getFileName().toString().startsWith(expectedStem));
		assertEquals(expectedStem + ".sbpt", sbpt.getFileName().toString());
		assertEquals(expectedStem + ".sbm", sbm.getFileName().toString());
		assertEquals("col1+col2", SealedIndexPathUtil.safeIndexFileName(COMPOSITE));

		bptree.close();
		bitmap.close();
	}
}
