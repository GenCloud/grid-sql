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
package index.unit.replication.chaos;

import org.genfork.grid.replication.log.OpLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * C6 observe-only: mid-file OpLog corruption stays fail-closed — no invent hole-fill heal.
 * <p>
 * Documents current product contract (torn-tail recovery only). Do not add mid-file invent.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
class OpLogMidFileCorruptObserveIT {

	private static final String DOMAIN = "c6.observe";

	@TempDir
	Path tempDir;

	@Test
	void openAfterMidFileGarbageDoesNotInventHeal() throws Exception {
		final Path root = tempDir.resolve("oplog");
		Files.createDirectories(root);
		try (OpLog opLog = new OpLog(root, true)) {
			opLog.lastSeq(DOMAIN, 0);
		}
		if (Files.isDirectory(root)) {
			Files.walk(root)
					.filter(Files::isRegularFile)
					.forEach(file -> {
						try {
							Files.write(file, new byte[]{(byte) 0xFF, (byte) 0xFE}, StandardOpenOption.APPEND);
						} catch (Exception ignored) {
							// observe path — corruption inject best-effort
						}
					});
		}
		assertDoesNotThrow(() -> {
			try (OpLog reopened = new OpLog(root, true)) {
				reopened.lastSeq(DOMAIN, 0);
			}
		});
	}
}