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

import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlServerRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DROP clears KEYS index-ckpt sidecars via {@code onTableDropped}; sealed retire is
 * {@code purgeDomainArtifacts} only.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.1
 */
class DropTableIndexCkptHygieneIT {
	private static final String TABLE = "ckpt_t";
	private static final String CKPT_BYTES_SUFFIX = ".bytes";

	@TempDir
	Path tempDir;

	@Test
	void dropRemovesIndexCkptSidecars() throws Exception {
		final Path dataDir = tempDir.resolve("drop-ckpt");
		try (SqlServerRuntime runtime = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(2)
				.durability(true)
				.build()) {
			final SqlEngine engine = runtime.engine();
			engine.execute("CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, v INT)");
			final Path ckptFile = findIndexCkptRoot(dataDir)
					.resolve(Integer.toHexString(TABLE.hashCode()) + CKPT_BYTES_SUFFIX);
			Files.createDirectories(ckptFile.getParent());
			Files.writeString(ckptFile, "stale", StandardCharsets.UTF_8);
			assertTrue(Files.isRegularFile(ckptFile));
			engine.execute("DROP TABLE " + TABLE);
			assertFalse(engine.catalog().exists(TABLE));
			assertFalse(Files.exists(ckptFile), "index-ckpt sidecar must be deleted on DROP");
		}
	}

	@Test
	void purgeDomainArtifactsApiAvailableOnDurableCoordinator() {
		final Path dataDir = tempDir.resolve("purge");
		try (SqlServerRuntime runtime = SqlServerRuntime.builder()
				.dataDir(dataDir)
				.shards(2)
				.durability(true)
				.build()) {
			runtime.engine().execute("CREATE TABLE purge_t (id INT PRIMARY KEY)");
			runtime.ownedReplication().purgeDomainArtifacts("purge_t");
			runtime.engine().execute("DROP TABLE purge_t");
			assertFalse(runtime.engine().catalog().exists("purge_t"));
		}
	}

	private static Path findIndexCkptRoot(Path dataDir) throws Exception {
		try (Stream<Path> walk = Files.walk(dataDir)) {
			return walk
					.filter(p -> p.getFileName() != null && "index-ckpt".equals(p.getFileName().toString()))
					.filter(Files::isDirectory)
					.findFirst()
					.orElse(dataDir.resolve("index-ckpt"));
		}
	}
}
