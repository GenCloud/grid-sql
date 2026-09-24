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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GridFs atomic publish + mmap helpers.
 *
 * @author: GenCloud
 * @date: 2026/12
 * @since: 1.0
 */
@DisplayName("GridFsIT")
class GridFsIT {
	private static final byte[] PAYLOAD = "grid-fs-payload".getBytes(StandardCharsets.UTF_8);

	@TempDir
	Path tmp;

	@Test
	void writeAtomic_publishesViaTmp() throws Exception {
		final Path target = tmp.resolve("sealed-like.dat");
		GridFs.writeAtomic(target, PAYLOAD);
		assertTrue(GridFs.isRegularFile(target));
		assertFalse(GridFs.exists(GridFs.siblingTmp(target)));
		assertArrayEquals(PAYLOAD, GridFs.readAll(target));
	}

	@Test
	void mapReadOnly_andUnmap() throws Exception {
		final Path file = tmp.resolve("map.bin");
		GridFs.writeAtomic(file, PAYLOAD);
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
			final MappedByteBuffer mapped = GridFs.mapReadOnly(channel, 0L, PAYLOAD.length);
			assertNotNull(mapped);
			final byte[] got = new byte[PAYLOAD.length];
			mapped.get(got);
			assertArrayEquals(PAYLOAD, got);
			GridFs.unmap(mapped);
		}
	}

	@Test
	void platform_detectsNonNull() {
		assertNotNull(Platform.current());
		assertEquals(Platform.isWindows(), Platform.current() == Platform.WINDOWS);
	}
}