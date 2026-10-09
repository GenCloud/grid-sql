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

import org.genfork.grid.mem.GridScalableMap;
import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapReader;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * DELETE must suppress sealed mmap resurrection so INSERT/SELECT do not see deleted keys.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.1
 */
class SealedDeleteSuppressIT {
	private static final String DOMAIN = "bitset_range";
	private static final int SHARD = 0;

	@TempDir
	Path temp;

	@Test
	void installCommittedDeleteSuppressesSealedMiss() throws Exception {
		final byte[] key = "k3".getBytes(StandardCharsets.UTF_8);
		final byte[] value = "ITEMS".getBytes(StandardCharsets.UTF_8);
		SealedGridMapWriter.writeNodes(temp, DOMAIN, SHARD, 11L,
				List.of(new SealedGridMapWriter.Kv(key, value)));

		final GridEntriesProcessor processor = new GridEntriesProcessor(SHARD, new GridScalableMap(), null, null);
		try (SealedGridMapReader reader = SealedGridMapReader.openShard(temp, DOMAIN, SHARD)) {
			processor.setSealedReader(reader);
			processor.putMapOnly(key, value);
			assertArrayEquals(value, processor.getCommitted(key));

			processor.installCommitted(key, null, true);

			assertNull(processor.getCommitted(key),
					"DELETE must not resurrect value from sealed after RAM remove");
		}
	}
}
