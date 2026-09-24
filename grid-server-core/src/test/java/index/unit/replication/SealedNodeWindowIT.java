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
package org.genfork.grid.replication.snapshot.sealed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
class SealedNodeWindowIT {
	@TempDir
	Path temp;

	@Test
	void remapsBoundedPayloadWindows() throws Exception {
		final int previousWindowBytes = SealedGridMapReader.WINDOW_BYTES;
		SealedGridMapReader.WINDOW_BYTES = 32;
		try {
			final byte[] key = "window-key".getBytes(StandardCharsets.UTF_8);
			final byte[] value = new byte[256];
			Arrays.fill(value, (byte) 7);
			SealedGridMapWriter.writeNodes(temp, "window", 2, 17L,
					List.of(new SealedGridMapWriter.Kv(key, value)));
			try (SealedGridMapReader reader = SealedGridMapReader.openShard(temp, "window", 2)) {
				SealedMetrics.SEALED_WINDOW_REMAP.set(0L);
				assertArrayEquals(value, reader.get(key));
				assertTrue(SealedMetrics.SEALED_WINDOW_REMAP.get() > 1L);
			}
		} finally {
			SealedGridMapReader.WINDOW_BYTES = previousWindowBytes;
		}
	}
}