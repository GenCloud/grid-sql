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

import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.mem.GridScalableMap;
import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapService;
import org.genfork.grid.replication.snapshot.sealed.SealedGridMapWriter;
import org.genfork.grid.replication.snapshot.sealed.SealedPackFingerprint;
import org.genfork.grid.replication.snapshot.sealed.SealedShardPack;
import org.genfork.grid.replication.util.SealedIntegritySensorUtil;
import org.genfork.grid.replication.util.SealedIntegritySensorUtil.Probe;
import org.genfork.grid.utils.ArrayUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sealed node-hole: magic-OK undersize / missing node → peer complete pack restores PK.
 * <p>
 * Product path: {@link SealedGridMapService#unpackShardArtifacts} + LAZY {@code getCommitted}.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
class SealedNodeHoleCatchUpIT {

	private static final String DOMAIN = "hole_tbl";
	private static final int SHARD = 0;
	private static final long WATERMARK = 17L;
	/** Keep VERSION_NODE=2 header while cutting payload (live 703-vs-955 shape). */
	private static final int SHRINK_TAIL_CUT_BYTES = 128;

	@TempDir
	Path tmp;

	@Test
	void magicOkShrinkThenCompletePackRestoresPk() throws Exception {
		final Path donorRoot = tmp.resolve("donor-sealed");
		Files.createDirectories(donorRoot);

		final List<SealedGridMapWriter.Kv> rows = new ArrayList<>();
		byte[] targetKey = null;
		byte[] targetVal = null;
		int targetNode = -1;
		for (int i = 0; i < 64; i++) {
			final byte[] key = new byte[]{(byte) i, (byte) (i + 1)};
			final byte[] val = new byte[]{(byte) (100 + i)};
			rows.add(new SealedGridMapWriter.Kv(key, val));
			if (targetKey == null) {
				targetKey = key;
				targetVal = val;
				targetNode = Math.abs(ArrayUtil.fastHash(key) % SealedGridMapWriter.NODE_COUNT);
			}
		}
		SealedGridMapWriter.writeNodes(donorRoot, DOMAIN, SHARD, WATERMARK, rows);
		final byte[] packed = SealedShardPack.pack(donorRoot, DOMAIN, SHARD);
		assertTrue(SealedShardPack.isCompleteShardArtifacts(packed));
		final long donorFp = SealedPackFingerprint.ofShardIdentity(donorRoot, DOMAIN, SHARD);

		final GridConfigurationProperties props = lazyProps(tmp.resolve("hole-repl"));
		final ReplicationCoordinator coord = new ReplicationCoordinator(props);
		final GridEntriesProcessor proc = new GridEntriesProcessor(SHARD, new GridScalableMap(), null, null);
		coord.registerDomain(DOMAIN, shard -> shard == SHARD ? proc : null, true);
		try {
			final SealedGridMapService sealed = coord.getSealedGridMapService();
			final Path sealedRoot = sealed.sealedRootOrNull();
			Files.createDirectories(sealedRoot);
			SealedShardPack.unpack(packed, sealedRoot);

			final String hex = SealedShardPack.domainHex(DOMAIN);
			final Path holeGmap = sealedRoot.resolve(hex + "_" + SHARD + "_n" + targetNode + ".gmap");
			assertTrue(Files.isRegularFile(holeGmap));
			final byte[] original = Files.readAllBytes(holeGmap);
			assertTrue(original.length > SealedIntegritySensorUtil.GMAP_FIXED_HEADER_BYTES + SHRINK_TAIL_CUT_BYTES);
			final int shrinkSize = original.length - SHRINK_TAIL_CUT_BYTES;
			Files.write(holeGmap, Arrays.copyOf(original, shrinkSize));

			assertEquals(Probe.OK, SealedIntegritySensorUtil.probeGmapHeader(holeGmap));
			assertEquals(Probe.OK, SealedIntegritySensorUtil.probeAllGmapHeaders(sealedRoot, DOMAIN, SHARD));
			final long holeFp = SealedPackFingerprint.ofShardIdentity(sealedRoot, DOMAIN, SHARD);
			assertNotEquals(donorFp, holeFp);
			assertTrue(SealedIntegritySensorUtil.needsPeerPack(
					SealedIntegritySensorUtil.combine(
							Probe.OK,
							SealedIntegritySensorUtil.probeFingerprintMismatch(holeFp, donorFp))));

			sealed.unpackShardArtifacts(DOMAIN, SHARD, packed);
			assertEquals(original.length, Files.size(holeGmap));
			assertEquals(donorFp, SealedPackFingerprint.ofShardIdentity(sealedRoot, DOMAIN, SHARD));

			coord.ensureShardHydrated(DOMAIN, SHARD);
			assertArrayEquals(targetVal, proc.getCommitted(targetKey));
		} finally {
			coord.stop();
		}
	}

	@Test
	void missingNodeThenCompletePackRestoresPk() throws Exception {
		final Path donorRoot = tmp.resolve("donor2-sealed");
		Files.createDirectories(donorRoot);
		final byte[] key = new byte[]{9, 9};
		final byte[] val = new byte[]{99};
		SealedGridMapWriter.writeNodes(donorRoot, DOMAIN, SHARD, WATERMARK,
				List.of(new SealedGridMapWriter.Kv(key, val)));
		final byte[] packed = SealedShardPack.pack(donorRoot, DOMAIN, SHARD);
		assertTrue(SealedShardPack.isCompleteShardArtifacts(packed));
		final int node = Math.abs(ArrayUtil.fastHash(key) % SealedGridMapWriter.NODE_COUNT);
		final String hex = SealedShardPack.domainHex(DOMAIN);

		final GridConfigurationProperties props = lazyProps(tmp.resolve("hole2-repl"));
		final ReplicationCoordinator coord = new ReplicationCoordinator(props);
		final GridEntriesProcessor proc = new GridEntriesProcessor(SHARD, new GridScalableMap(), null, null);
		coord.registerDomain(DOMAIN, shard -> shard == SHARD ? proc : null, true);
		try {
			final Path sealedRoot = coord.getSealedGridMapService().sealedRootOrNull();
			Files.createDirectories(sealedRoot);
			SealedShardPack.unpack(packed, sealedRoot);
			final Path nodeFile = sealedRoot.resolve(hex + "_" + SHARD + "_n" + node + ".gmap");
			Files.deleteIfExists(nodeFile);
			assertEquals(Probe.MISSING, SealedIntegritySensorUtil.probeGmapHeader(nodeFile));
			assertTrue(SealedIntegritySensorUtil.needsPeerPack(
					SealedIntegritySensorUtil.probeAllGmapHeaders(sealedRoot, DOMAIN, SHARD)));

			coord.getSealedGridMapService().unpackShardArtifacts(DOMAIN, SHARD, packed);
			coord.ensureShardHydrated(DOMAIN, SHARD);
			assertArrayEquals(val, proc.getCommitted(key));
		} finally {
			coord.stop();
		}
	}

	@Test
	void incompletePackRejectedWithoutAdvance() throws Exception {
		final byte[] headerOnly = new byte[SealedShardPack.HEADER_BYTES];
		ByteBuffer.wrap(headerOnly).order(ByteOrder.BIG_ENDIAN)
				.putInt(SealedShardPack.MAGIC)
				.putInt(SealedShardPack.VERSION)
				.putInt(0);
		assertTrue(!SealedShardPack.isCompleteShardArtifacts(headerOnly));

		final GridConfigurationProperties props = lazyProps(tmp.resolve("inc-repl"));
		final ReplicationCoordinator coord = new ReplicationCoordinator(props);
		coord.registerDomain(DOMAIN, shard -> shard == SHARD
				? new GridEntriesProcessor(SHARD, new GridScalableMap(), null, null)
				: null, true);
		try {
			boolean failed = false;
			try {
				coord.getSealedGridMapService().unpackShardArtifacts(DOMAIN, SHARD, headerOnly);
			} catch (Exception expected) {
				failed = true;
			}
			assertTrue(failed, "incomplete pack must fail closed");
		} finally {
			coord.stop();
		}
	}

	private static GridConfigurationProperties lazyProps(Path dataDir) {
		final GridConfigurationProperties props = new GridConfigurationProperties();
		props.getDurability().setEnabled(true);
		props.getDurability().setHydrateMode("LAZY");
		props.getReplication().setEnabled(false);
		props.getReplication().getOpLog().setDataDir(dataDir.toString());
		props.getReplication().setClusterId("c1");
		props.getReplication().setNodeId("n1");
		return props;
	}
}
