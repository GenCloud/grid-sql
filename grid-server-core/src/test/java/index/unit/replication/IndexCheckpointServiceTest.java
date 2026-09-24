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

import org.genfork.grid.replication.snapshot.IndexCheckpointService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class IndexCheckpointServiceTest {

	@TempDir
	Path tempDir;

	@Test
	void markAndLoadThroughSeq() {
		final IndexCheckpointService svc = new IndexCheckpointService(tempDir);
		assertEquals(0L, svc.loadThroughSeq("demo.Domain"));
		svc.markRebuilt("demo.Domain", 99L);
		assertEquals(99L, svc.loadThroughSeq("demo.Domain"));
	}

	@Test
	void idxSidecarEntryCount() {
		final IndexCheckpointService svc = new IndexCheckpointService(tempDir);
		assertEquals(-1L, svc.loadIdxEntryCount("demo.Domain"));
		svc.markRebuilt("demo.Domain", 50L, 1234L);
		assertEquals(50L, svc.loadThroughSeq("demo.Domain"));
		assertEquals(1234L, svc.loadIdxEntryCount("demo.Domain"));
	}
}
