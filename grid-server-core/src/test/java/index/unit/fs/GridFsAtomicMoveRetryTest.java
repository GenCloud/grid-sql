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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Windows REPLACE AccessDenied retry / fail-closed budget for {@link GridFs#moveAtomic}.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
@DisplayName("GridFsAtomicMoveRetry")
class GridFsAtomicMoveRetryTest {
	private static final String BODY_V1 = "v1";
	private static final String BODY_V2 = "v2";
	private static final int INJECT_TRANSIENT_DENIALS = 3;
	private static final int RAPID_REPLACE_LOOPS = 64;

	@TempDir
	Path tmp;

	@AfterEach
	void clearInject() {
		GridFs.clearInjectAccessDenied();
	}

	@Test
	@DisplayName("injected_AccessDenied_retries_then_succeeds_with_windowsRetry")
	void injectedAccessDeniedRetriesThenSucceeds() throws Exception {
		final Path target = tmp.resolve("meta.bin");
		final Path source = tmp.resolve("meta.bin.tmp.1");
		Files.writeString(target, BODY_V1, StandardCharsets.UTF_8);
		Files.writeString(source, BODY_V2, StandardCharsets.UTF_8);
		GridFs.injectAccessDeniedRemaining(INJECT_TRANSIENT_DENIALS);
		GridFs.moveAtomic(source, target, true);
		assertEquals(BODY_V2, Files.readString(target, StandardCharsets.UTF_8));
		assertEquals(0, GridFs.injectAccessDeniedRemaining());
	}

	@Test
	@DisplayName("injected_AccessDenied_exhausts_budget_fail_closed")
	void injectedAccessDeniedExhaustsBudget() throws Exception {
		final Path target = tmp.resolve("meta2.bin");
		final Path source = tmp.resolve("meta2.bin.tmp.1");
		Files.writeString(target, BODY_V1, StandardCharsets.UTF_8);
		Files.writeString(source, BODY_V2, StandardCharsets.UTF_8);
		GridFs.injectAccessDeniedRemaining(GridFs.windowsReplaceMaxAttempts() + 1);
		assertThrows(AccessDeniedException.class,
				() -> GridFs.moveAtomic(source, target, true));
		assertEquals(BODY_V1, Files.readString(target, StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("non_windows_retry_off_fails_on_first_injected_AccessDenied")
	void nonWindowsRetryOffFailsImmediately() throws Exception {
		final Path target = tmp.resolve("meta3.bin");
		final Path source = tmp.resolve("meta3.bin.tmp.1");
		Files.writeString(target, BODY_V1, StandardCharsets.UTF_8);
		Files.writeString(source, BODY_V2, StandardCharsets.UTF_8);
		GridFs.injectAccessDeniedRemaining(INJECT_TRANSIENT_DENIALS);
		assertThrows(AccessDeniedException.class,
				() -> GridFs.moveAtomic(source, target, false));
		assertEquals(BODY_V1, Files.readString(target, StandardCharsets.UTF_8));
	}

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
