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

import org.genfork.grid.replication.snapshot.sealed.SealedBPTreeWriter;
import org.genfork.grid.replication.snapshot.sealed.SealedLazyIndexRebuildUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Incomplete {@code .sbpt} accelerator detection (missing / empty shell / healthy).
 * Empty shells are absent for bind / at-risk ({@code entryCount == 0}).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
class SealedLazyIndexRebuildUtilTest {

	private static final String DOMAIN = "lazy_rebuild_util";
	private static final String INDEX = "acct";
	private static final int SHARD = 0;

	@TempDir
	Path tempDir;

	@Test
	void incompleteWhenMissingOrTruncatedOrEmptyShell() throws Exception {
		final Path missing = tempDir.resolve("no-such.sbpt");
		assertTrue(SealedLazyIndexRebuildUtil.isAcceleratorIncomplete(missing));
		assertFalse(SealedLazyIndexRebuildUtil.isParseableSealedIndexFile(missing));
		assertFalse(SealedLazyIndexRebuildUtil.isAuthoritativeSealedIndexFile(missing));

		final Path truncated = tempDir.resolve("trunc.sbpt");
		Files.write(truncated, new byte[]{1, 2, 3});
		assertTrue(SealedLazyIndexRebuildUtil.isAcceleratorIncomplete(truncated));
		assertFalse(SealedLazyIndexRebuildUtil.isAuthoritativeSealedIndexFile(truncated));

		final Path emptyShell = tempDir.resolve("empty.sbpt");
		SealedBPTreeWriter.write(emptyShell, DOMAIN, SHARD, INDEX, List.of());
		assertTrue(SealedLazyIndexRebuildUtil.isParseableSealedIndexFile(emptyShell));
		assertTrue(SealedLazyIndexRebuildUtil.isAcceleratorIncomplete(emptyShell));
		assertFalse(SealedLazyIndexRebuildUtil.isAuthoritativeSealedIndexFile(emptyShell),
				"empty shell must not be authoritative for bind/at-risk");
	}

	@Test
	void completeWhenSbptHasEntries() throws Exception {
		final Path healthy = tempDir.resolve("healthy.sbpt");
		final byte[] indexKey = "acc1".getBytes(StandardCharsets.UTF_8);
		final byte[] rowKey = new byte[]{0, 0, 0, 1};
		SealedBPTreeWriter.write(
				healthy,
				DOMAIN,
				SHARD,
				INDEX,
				List.of(new SealedBPTreeWriter.Entry(indexKey, rowKey)));
		assertTrue(SealedLazyIndexRebuildUtil.isParseableSealedIndexFile(healthy));
		assertTrue(SealedLazyIndexRebuildUtil.isAuthoritativeSealedIndexFile(healthy));
		assertFalse(SealedLazyIndexRebuildUtil.isAcceleratorIncomplete(healthy));
	}
}
