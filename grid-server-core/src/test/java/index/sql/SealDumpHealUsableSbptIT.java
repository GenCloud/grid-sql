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
package index.sql;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.snapshot.sealed.SealedBPTreeWriter;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapService;
import org.genfork.grid.replication.snapshot.sealed.SealedShardPack;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlServerRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Choke sealed dump with a real secondary index must leave usable {@code .sbpt}
 * ({@link SealedGridMapService#hasUsableSealedIndex}), including huge tip after empty shells.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
class SealDumpHealUsableSbptIT {
	private static final String TABLE = "seal_heal_usable";
	private static final String INDEX = "seal_heal_usable_acct";
	private static final String ACCOUNT_COL = "account_name";
	private static final String ACCOUNT = "acc1";
	private static final int SHARDS = 4;
	private static final String HYDRATE_LAZY = "LAZY";
	private static final long MAX_OPLOG_DELTA_WITHOUT_SBPT = 100_000L;
	private static final long PAD_SEQ = MAX_OPLOG_DELTA_WITHOUT_SBPT + 77L;

	@TempDir(cleanup = CleanupMode.NEVER)
	Path tempDir;

	@Test
	void hugeTipAtRiskDumpProducesUsableSbptWithSecondaryIndex() throws Exception {
		final Path dataDir = tempDir.resolve("seal-heal-usable");
		sealOnce(dataDir);
		blankSecondarySbpt(dataDir, TABLE, ACCOUNT_COL);
		padTipAndHealViaLazyTouch(dataDir);
	}

	private static void sealOnce(Path dataDir) {
		try (SqlServerRuntime runtime = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(SHARDS)
				.durability(true)
				.hydrateMode(HYDRATE_LAZY)
				.build()) {
			final SqlEngine engine = runtime.engine();
			engine.execute("CREATE TABLE " + TABLE
					+ " (id INT PRIMARY KEY, account_name VARCHAR, server_id INT)");
			engine.execute("CREATE INDEX " + INDEX + " ON " + TABLE + " (account_name)");
			engine.execute("INSERT INTO " + TABLE
					+ " (id, account_name, server_id) VALUES (1, '" + ACCOUNT + "', 3)");

			final ReplicationCoordinator coord = runtime.replication();
			assertNotNull(coord);
			final SealedGridMapService sealed = coord.getSealedGridMapService();
			assertNotNull(sealed);
			assertTrue(coord.dumpDomainSnapshot(TABLE) > 0,
					"choke dump must seal mergeable catalog rows");
			assertTrue(anyUsableSbpt(sealed), "dump with compositeIndex must leave usable .sbpt");
		}
	}

	private static void padTipAndHealViaLazyTouch(Path dataDir) throws IOException {
		try (SqlServerRuntime runtime = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(SHARDS)
				.durability(true)
				.hydrateMode(HYDRATE_LAZY)
				.build()) {
			final ReplicationCoordinator coord = runtime.replication();
			assertNotNull(coord);
			final SealedGridMapService sealed = coord.getSealedGridMapService();
			assertNotNull(sealed);

			// Pad only shards that already have sealed .gmap rows (empty-merge tip cannot invent .sbpt).
			final List<Integer> sealedShards = listSealedGmapShards(dataDir, TABLE);
			assertFalse(sealedShards.isEmpty(), "expected sealed .gmap after first seal");
			for (Integer shardObj : sealedShards) {
				final int shard = shardObj.intValue();
				coord.getOpLog().append(OpLogCodec.withChecksum(new ReplicationOp(
						TABLE, shard, PAD_SEQ, ReplicationOpType.TX_COMMIT,
						("pad-tx-" + shard).getBytes(StandardCharsets.UTF_8),
						null,
						1L, 0L)));
			}

			final SqlEngine engine = runtime.engine();
			final SqlResult byAcct = engine.execute(
					"SELECT id FROM " + TABLE
							+ " WHERE account_name = '" + ACCOUNT + "' AND server_id = 3");
			assertFalse(byAcct.rows().isEmpty(), "LAZY touch must heal empty .sbpt under huge tip");
			assertEquals(1, ((Number) byAcct.rows().getFirst()[0]).intValue());
			assertTrue(anyUsableSbpt(sealed),
					"huge tip + real indexes must leave authoritative .sbpt entryCount>0");
		}
	}

	private static List<Integer> listSealedGmapShards(Path dataDir, String domain) throws IOException {
		final String hex = SealedShardPack.domainHex(domain);
		final String prefix = hex + "_";
		try (Stream<Path> walk = Files.walk(dataDir)) {
			return walk
					.map(p -> p.getFileName().toString())
					.filter(name -> name.startsWith(prefix) && name.contains("_n") && name.endsWith(".gmap"))
					.map(name -> {
						final int start = prefix.length();
						final int end = name.indexOf('_', start);
						return Integer.valueOf(name.substring(start, end));
					})
					.distinct()
					.toList();
		}
	}

	private static boolean anyUsableSbpt(SealedGridMapService sealed) {
		for (int shard = 0; shard < SHARDS; shard++) {
			if (sealed.hasUsableSealedIndex(TABLE, shard)) {
				return true;
			}
		}
		return false;
	}

	private static void blankSecondarySbpt(Path dataDir, String domain, String indexName)
			throws IOException {
		final String hex = SealedShardPack.domainHex(domain);
		final String needle = "_idx_" + indexName + ".sbpt";
		final List<Path> targets;
		try (Stream<Path> walk = Files.walk(dataDir)) {
			targets = walk
					.filter(p -> {
						final String name = p.getFileName().toString();
						return name.startsWith(hex + "_") && name.endsWith(needle);
					})
					.toList();
		}
		assertFalse(targets.isEmpty(), "no secondary .sbpt to blank under " + dataDir);
		for (Path sbpt : targets) {
			final String name = sbpt.getFileName().toString();
			final int shard = parseShard(hex, name);
			SealedBPTreeWriter.write(sbpt, domain, shard, indexName, List.of());
		}
	}

	private static int parseShard(String hex, String fileName) {
		final String prefix = hex + "_";
		assertTrue(fileName.startsWith(prefix), fileName);
		final int idxStart = fileName.indexOf("_idx_");
		assertTrue(idxStart > prefix.length(), fileName);
		return Integer.parseInt(fileName.substring(prefix.length(), idxStart));
	}
}
