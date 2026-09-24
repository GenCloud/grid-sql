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

import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GridFs atomic write / read / mmap / platform smoke.
 *
 * @author: GenCloud
 * @date: 2026/12
 * @since: 1.0
 */
@DisplayName("GridFs")
class GridFsAtomicWriteTest {
	private static final String BODY = "hi=1\n";
	private static final byte[] PAYLOAD = new byte[] { 1, 2, 3, 4 };

	@TempDir
	Path tmp;

	@Test
	@DisplayName("writeAtomic_stringRoundTrip")
	void writeAtomic_stringRoundTrip() throws Exception {
		final Path target = tmp.resolve("meta.txt");
		GridFs.writeAtomic(target, BODY, StandardCharsets.UTF_8);
		assertTrue(GridFs.isRegularFile(target));
		assertFalse(GridFs.exists(GridFs.siblingTmp(target)));
		assertEquals(BODY, GridFs.readString(target));
		assertEquals(List.of("hi=1"), GridFs.readLines(target));
		assertNotNull(Platform.current());
	}

	@Test
	@DisplayName("writeAtomic_bytesAndMapReadOnly")
	void writeAtomic_bytesAndMapReadOnly() throws Exception {
		final Path target = tmp.resolve("blob.bin");
		GridFs.createDirs(tmp.resolve("nested"));
		GridFs.writeAtomic(target, PAYLOAD);
		assertArrayEquals(PAYLOAD, GridFs.readAll(target));
		try (FileChannel channel = FileChannel.open(target, StandardOpenOption.READ)) {
			final MappedByteBuffer mapped = GridFs.mapReadOnly(channel, 0L, PAYLOAD.length);
			try {
				assertEquals(PAYLOAD.length, mapped.remaining());
				final byte[] got = new byte[PAYLOAD.length];
				mapped.get(got);
				assertArrayEquals(PAYLOAD, got);
			} finally {
				GridFs.unmap(mapped);
			}
		}
	}
}