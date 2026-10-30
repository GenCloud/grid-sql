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
package index.unit.fs;

import org.genfork.grid.fs.GridFs;
import org.genfork.grid.fs.Platform;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rapid REPLACE publish loop for {@link GridFs#writeAtomic}.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
@DisplayName("GridFsAtomicMoveRetry")
class GridFsAtomicMoveRetryTest {
	private static final String BODY_V1 = "v1";
	private static final int RAPID_REPLACE_LOOPS = 64;

	@TempDir
	Path tmp;

	@Test
	@DisplayName("writeAtomic_rapid_replace_loop_publishes")
	void writeAtomicRapidReplaceLoop() throws Exception {
		final Path target = tmp.resolve("privileges.meta");
		GridFs.writeAtomic(target, BODY_V1, StandardCharsets.UTF_8);
		for (int i = 0; i < RAPID_REPLACE_LOOPS; i++) {
			final String body = "loop-" + i;
			GridFs.writeAtomic(target, body, StandardCharsets.UTF_8);
			assertEquals(body, GridFs.readString(target));
		}
		assertTrue(Platform.current() != null);
	}
}
