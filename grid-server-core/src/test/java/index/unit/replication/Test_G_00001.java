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

import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.fs.GridFs;
import org.genfork.grid.mem.GridScalableMap;
import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapReader;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapService;
import org.genfork.grid.utils.ArrayUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for G-00001: sealed checkpoint must unmap live reader before rewrite.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
@DisplayName("Test_G_00001")
class Test_G_00001 {
	private static final String DOMAIN_DOUBLE_DUMP = "g00001_double";
	private static final String DOMAIN_MULTI_SHARD = "g00001_shard";
	private static final int SHARD_ZERO = 0;
	private static final int SHARD_ONE = 1;
	private static final int SHARD_COUNT = 2;
	private static final int KEY_SEARCH_LIMIT = 10_000;
	private static final byte[] VALUE_V1 = "v1".getBytes(StandardCharsets.UTF_8);
	private static final byte[] VALUE_V2 = "v2".getBytes(StandardCharsets.UTF_8);

	@TempDir
	Path tmp;

	@Test
	@DisplayName("test_G_00001_doubleDumpWithLiveReader_doesNotThrow")
	void test_G_00001_doubleDumpWithLiveReader_doesNotThrow() throws Exception {
		final Path sealedRoot = tmp.resolve("sealed-double");
		GridFs.createDirs(sealedRoot);
		final SealedGridMapService service = new SealedGridMapService(sealedRoot);
		final GridEntriesProcessor processor = new GridEntriesProcessor(
				SHARD_ZERO, new GridScalableMap(), null, null);
		service.bindProcessor(DOMAIN_DOUBLE_DUMP, shard -> shard == SHARD_ZERO ? processor : null);

		final byte[] key = "live-key".getBytes(StandardCharsets.UTF_8);
		processor.installCommitted(key, VALUE_V1, false);

		final int firstRows = service.dumpDomain(DOMAIN_DOUBLE_DUMP, null, null);
		assertTrue(firstRows > 0);
		final SealedGridMapReader live = service.reader(DOMAIN_DOUBLE_DUMP, SHARD_ZERO);
		assertNotNull(live);
		assertArrayEquals(VALUE_V1, live.get(key));

		processor.installCommitted(key, VALUE_V2, false);
		assertDoesNotThrow(() -> service.dumpDomain(DOMAIN_DOUBLE_DUMP, null, null));

		final SealedGridMapReader after = service.reader(DOMAIN_DOUBLE_DUMP, SHARD_ZERO);
		assertNotNull(after);
		assertArrayEquals(VALUE_V2, after.get(key));
		service.closeAll();
	}

	@Test
	@DisplayName("test_G_00001_checkpointAfterInsert_shardStable")
	void test_G_00001_checkpointAfterInsert_shardStable() {
		final GridConfigurationProperties props = new GridConfigurationProperties();
		props.getDurability().setEnabled(true);
		props.getDurability().setHydrateMode("LAZY");
		props.getReplication().setEnabled(false);
		props.getReplication().getOpLog().setDataDir(tmp.resolve("repl").toString());
		props.getReplication().setClusterId("g00001");
		props.getReplication().setNodeId("n1");

		final GridEntriesProcessor shard0 = new GridEntriesProcessor(
				SHARD_ZERO, new GridScalableMap(), null, null);
		final GridEntriesProcessor shard1 = new GridEntriesProcessor(
				SHARD_ONE, new GridScalableMap(), null, null);
		final Function<Integer, GridEntriesProcessor> byShard = shard -> {
			if (shard == SHARD_ZERO) {
				return shard0;
			}
			if (shard == SHARD_ONE) {
				return shard1;
			}
			return null;
		};

		final ReplicationCoordinator coord = new ReplicationCoordinator(props);
		coord.registerDomain(DOMAIN_MULTI_SHARD, byShard, true);

		final byte[] keyOnShardOne = keyForShard(SHARD_ONE, SHARD_COUNT);
		shard1.installCommitted(keyOnShardOne, VALUE_V1, false);

		assertDoesNotThrow(() -> coord.dumpDomainSnapshot(DOMAIN_MULTI_SHARD));
		assertDoesNotThrow(() -> coord.dumpDomainSnapshot(DOMAIN_MULTI_SHARD));

		final SealedGridMapReader reader = coord.getSealedGridMapService()
				.reader(DOMAIN_MULTI_SHARD, SHARD_ONE);
		assertNotNull(reader);
		assertArrayEquals(VALUE_V1, reader.get(keyOnShardOne));

		coord.stop();
	}

	private static byte[] keyForShard(int targetShard, int shardCount) {
		for (int i = 0; i < KEY_SEARCH_LIMIT; i++) {
			final byte[] key = ("g1-k" + i).getBytes(StandardCharsets.UTF_8);
			if (Math.abs(ArrayUtil.fastHash(key) % shardCount) == targetShard) {
				return key;
			}
		}
		throw new IllegalStateException("no key for shard " + targetShard + " in " + KEY_SEARCH_LIMIT);
	}
}