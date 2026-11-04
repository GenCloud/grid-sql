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

import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.repair.HomologousRepair;
import org.genfork.grid.replication.repair.VersionLocus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Corrupt row bytes → FETCH_ROW reconcile → healCorruptedRow restores value checksum.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class RowCorruptHealIT {

	@TempDir
	Path tempDir;

	@Test
	void corruptRowBytesFetchRowHeals() {
		final HomologousRepair local = new HomologousRepair(tempDir.resolve("locus-a"));
		final HomologousRepair remote = new HomologousRepair(tempDir.resolve("locus-b"));

		final byte[] key = new byte[]{7, 7, 7};
		final byte[] value = new byte[]{1, 2, 3, 4};
		final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
				"chaos.Row", 0, 5L, ReplicationOpType.UPSERT, key, value, 1L, 0L
		));
		local.observe(op);
		remote.observe(op);

		final long hash = HomologousRepair.keyHash(key);
		final VersionLocus good = remote.getLocusMap().snapshot("chaos.Row", 0).get(hash);
		final byte[] goodBytes = value;
		final byte[] badLocal = new byte[]{0x0D, (byte) 0xE0};

		final byte[] healed = local.healCorruptedRow(
				"chaos.Row", 0, hash, good.opSeq(), good.schemaEpoch(),
				badLocal, goodBytes, good.contentChecksum()
		);
		assertArrayEquals(goodBytes, healed);
		assertEquals(good.contentChecksum(),
				local.getLocusMap().snapshot("chaos.Row", 0).get(hash).contentChecksum());
		assertTrue(local.repairApplied() >= 1);
	}
}
