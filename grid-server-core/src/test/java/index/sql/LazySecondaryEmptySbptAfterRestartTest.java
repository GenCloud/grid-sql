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

import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.snapshot.sealed.SealedBPTreeReader;
import org.genfork.grid.replication.snapshot.sealed.SealedBPTreeWriter;
import org.genfork.grid.replication.snapshot.sealed.SealedShardPack;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlServerRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LAZY reopen with durable {@code .gmap} rows but empty/missing secondary {@code .sbpt}
 * (DDL-time / deferred-seal shells): hydrate heals via sealed dump (not RAM-on-open);
 * lobby-shaped {@code account_name} EQ must see rows and disk {@code entryCount > 0}.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
class LazySecondaryEmptySbptAfterRestartTest {

	private static final String TABLE = "lazy_empty_sbpt";
	private static final String INDEX = "lazy_empty_sbpt_acct";
	private static final int SHARDS = 4;
	private static final String HYDRATE_LAZY = "LAZY";
	private static final String ACCOUNT = "acc1";
	private static final String ACCOUNT_COL = "account_name";

	@TempDir(cleanup = CleanupMode.NEVER)
	Path tempDir;

	@Test
	void secondaryEqSeesRowsWhenSbptEmptyButGmapHasData() throws Exception {
		final Path dataDir = tempDir.resolve("lazy-empty-sbpt");
		sealTwoRows(dataDir);
		blankSecondarySbpt(dataDir, TABLE, ACCOUNT_COL);
		assertLobbySecondaryEq(dataDir);
		assertSecondarySbptHasEntries(dataDir, TABLE, ACCOUNT_COL);
	}

	@Test
	void secondaryEqSeesRowsWhenSbptDeletedButGmapHasData() throws Exception {
		final Path dataDir = tempDir.resolve("lazy-deleted-sbpt");
		sealTwoRows(dataDir);
		deleteSecondarySbpt(dataDir, TABLE, ACCOUNT_COL);
		assertLobbySecondaryEq(dataDir);
		assertSecondarySbptHasEntries(dataDir, TABLE, ACCOUNT_COL);
	}

	private static void sealTwoRows(Path dataDir) {
		try (SqlServerRuntime first = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(SHARDS)
				.durability(true)
				.hydrateMode(HYDRATE_LAZY)
				.build()) {
			final SqlEngine engine = first.engine();
			engine.execute("CREATE TABLE " + TABLE
					+ " (id INT PRIMARY KEY, account_name VARCHAR, server_id INT)");
			engine.execute("CREATE INDEX " + INDEX + " ON " + TABLE + " (account_name)");
			engine.execute("INSERT INTO " + TABLE
					+ " (id, account_name, server_id) VALUES (1, '" + ACCOUNT + "', 3)");
			engine.execute("INSERT INTO " + TABLE
					+ " (id, account_name, server_id) VALUES (2, 'acc2', 3)");
			final ReplicationCoordinator coord = first.replication();
			assertNotNull(coord);
			assertTrue(coord.dumpDomainSnapshot(TABLE) >= 2, "seal must persist rows into .gmap");
		}
	}

	private static void assertLobbySecondaryEq(Path dataDir) {
		try (SqlServerRuntime second = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(SHARDS)
				.durability(true)
				.hydrateMode(HYDRATE_LAZY)
				.build()) {
			final SqlEngine engine = second.engine();
			// Lobby shape first: must NOT warm RAM via PK get (that reindexes secondary).
			final SqlResult byAcct = engine.execute(
					"SELECT id FROM " + TABLE
							+ " WHERE account_name = '" + ACCOUNT + "' AND server_id = 3");
			assertFalse(byAcct.rows().isEmpty(),
					"secondary EQ empty with incomplete .sbpt (lobby account_name shape)");
			assertEquals(1, ((Number) byAcct.rows().getFirst()[0]).intValue());

			final SqlResult byPk = engine.execute(
					"SELECT account_name FROM " + TABLE + " WHERE id = 1");
			assertFalse(byPk.rows().isEmpty(), "PK SELECT empty — .gmap lost?");
			assertEquals(ACCOUNT, byPk.rows().getFirst()[0]);
		}
	}

	/**
	 * Overwrite every shard's account_name {@code .sbpt} with a valid empty tree
	 * (same shape as DDL-time / ENVELOPE-deferred seal shells ~one page).
	 */
	private static void blankSecondarySbpt(Path dataDir, String domain, String indexName)
			throws IOException {
		final List<Path> targets = listSecondarySbpt(dataDir, domain, indexName);
		assertFalse(targets.isEmpty(), "no account_name .sbpt to blank under " + dataDir);
		for (Path sbpt : targets) {
			final String name = sbpt.getFileName().toString();
			final int shard = parseShard(SealedShardPack.domainHex(domain), name);
			SealedBPTreeWriter.write(sbpt, domain, shard, indexName, List.of());
		}
	}

	private static void deleteSecondarySbpt(Path dataDir, String domain, String indexName)
			throws IOException {
		final List<Path> targets = listSecondarySbpt(dataDir, domain, indexName);
		assertFalse(targets.isEmpty(), "no account_name .sbpt to delete under " + dataDir);
		for (Path sbpt : targets) {
			Files.deleteIfExists(sbpt);
		}
	}

	/** After heal dump, at least one secondary {@code .sbpt} must be non-empty on disk. */
	private static void assertSecondarySbptHasEntries(Path dataDir, String domain, String indexName)
			throws IOException {
		final List<Path> targets = listSecondarySbpt(dataDir, domain, indexName);
		assertFalse(targets.isEmpty(), "no account_name .sbpt after heal under " + dataDir);
		boolean anyEntries = false;
		for (Path sbpt : targets) {
			try (SealedBPTreeReader peek = SealedBPTreeReader.open(sbpt)) {
				if (peek.entryCount() > 0L) {
					anyEntries = true;
					break;
				}
			}
		}
		assertTrue(anyEntries, "heal dump must write non-empty secondary .sbpt");
	}

	private static List<Path> listSecondarySbpt(Path dataDir, String domain, String indexName)
			throws IOException {
		final String hex = SealedShardPack.domainHex(domain);
		final String needle = "_idx_" + indexName + ".sbpt";
		try (Stream<Path> walk = Files.walk(dataDir)) {
			return walk
					.filter(p -> {
						final String name = p.getFileName().toString();
						return name.startsWith(hex + "_") && name.endsWith(needle);
					})
					.toList();
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
