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

import org.genfork.grid.replication.log.OpLog;
import org.genfork.grid.replication.util.HelloSealedCatchUpUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit checks for HELLO sealed baseline gate past OpLog truncate.
 *
 * @author: GenCloud
 * @date: 2026/09
 * @since: 1.0
 */
public class HelloSealedCatchUpUtilTest {

	private static final String DOMAIN = "hello.seal";

	@TempDir(cleanup = CleanupMode.NEVER)
	Path tempDir;

	@Test
	void needsSealedBaselineWhenPeerBehindTruncate() throws Exception {
		try (OpLog opLog = new OpLog(tempDir.resolve("oplog"), true)) {
			assertFalse(HelloSealedCatchUpUtil.needsSealedBaseline(opLog, DOMAIN, 0, 0L));
			opLog.truncateTo(DOMAIN, 0, 5L);
			assertTrue(HelloSealedCatchUpUtil.needsSealedBaseline(opLog, DOMAIN, 0, 0L));
			assertTrue(HelloSealedCatchUpUtil.needsSealedBaseline(opLog, DOMAIN, 0, 4L));
			assertFalse(HelloSealedCatchUpUtil.needsSealedBaseline(opLog, DOMAIN, 0, 5L));
			assertFalse(HelloSealedCatchUpUtil.needsSealedBaseline(opLog, DOMAIN, 0, 6L));
			assertFalse(HelloSealedCatchUpUtil.needsSealedBaseline(null, DOMAIN, 0, 0L));
		}
	}
}