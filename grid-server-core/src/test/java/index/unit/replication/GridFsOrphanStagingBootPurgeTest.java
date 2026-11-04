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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boot-only orphan {@code *.tmp.*} / {@code *.part} purge — never promotes staging.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
class GridFsOrphanStagingBootPurgeTest {

	@TempDir
	Path tempDir;

	@Test
	void purgeDeletesTmpAndPartLeavesCanonical() throws Exception {
		final Path sealed = tempDir.resolve("sealed");
		Files.createDirectories(sealed);
		final Path canonical = sealed.resolve("a_0_n0.gmap");
		final Path tmp = sealed.resolve("a_0_n0.gmap.tmp.1.2");
		final Path part = sealed.resolve("a_0_n0.gmap.part");
		Files.writeString(canonical, "ok", StandardCharsets.UTF_8);
		Files.writeString(tmp, "staging", StandardCharsets.UTF_8);
		Files.writeString(part, "legacy", StandardCharsets.UTF_8);

		assertTrue(GridFs.isOrphanStagingName(tmp));
		assertTrue(GridFs.isOrphanStagingName(part));
		assertFalse(GridFs.isOrphanStagingName(canonical));

		final int deleted = GridFs.purgeOrphanStagingAtBoot(tempDir);
		assertEquals(2, deleted);
		assertTrue(Files.exists(canonical));
		assertFalse(Files.exists(tmp));
		assertFalse(Files.exists(part));
	}
}
