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
import org.genfork.grid.replication.snapshot.sealed.SealedBPTreeReader;
import org.genfork.grid.replication.snapshot.sealed.SealedBPTreeService;
import org.genfork.grid.replication.snapshot.sealed.SealedBPTreeWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Regression: sealed .sbpt rewrite must unmap live reader before Windows ATOMIC_MOVE REPLACE.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
@DisplayName("Test_G_00001_sbpt")
class Test_G_00001_SbptRewrite {
	private static final String DOMAIN = "g00001_sbpt";
	private static final int SHARD = 0;
	private static final String INDEX = "id";

	@TempDir
	Path tmp;

	@Test
	@DisplayName("test_G_00001_doubleDumpSbptWithLiveReader_doesNotThrow")
	void test_G_00001_doubleDumpSbptWithLiveReader_doesNotThrow() throws Exception {
		final Path sealedRoot = tmp.resolve("sealed-sbpt");
		GridFs.createDirs(sealedRoot);
		final SealedBPTreeService service = new SealedBPTreeService(sealedRoot);

		final byte[] indexKey = "1".getBytes(StandardCharsets.UTF_8);
		final byte[] rowKey = "row-1".getBytes(StandardCharsets.UTF_8);
		final List<SealedBPTreeWriter.Entry> first = List.of(new SealedBPTreeWriter.Entry(indexKey, rowKey));
		service.dumpIndex(DOMAIN, SHARD, INDEX, first);
		final SealedBPTreeReader live = service.open(DOMAIN, SHARD, INDEX);
		assertNotNull(live);

		final List<SealedBPTreeWriter.Entry> second = List.of(
				new SealedBPTreeWriter.Entry(indexKey, rowKey),
				new SealedBPTreeWriter.Entry("2".getBytes(StandardCharsets.UTF_8),
						"row-2".getBytes(StandardCharsets.UTF_8)));
		assertDoesNotThrow(() -> service.dumpIndex(DOMAIN, SHARD, INDEX, second));
		assertNotNull(service.open(DOMAIN, SHARD, INDEX));
		service.close();
	}
}