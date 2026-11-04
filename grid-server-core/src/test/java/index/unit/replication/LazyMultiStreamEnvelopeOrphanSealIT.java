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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.mem.GridScalableMap;
import org.genfork.grid.mem.stage.GridEntriesProcessor;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.apply.ReplicaApplier;
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.snapshot.sealed.SealedDumpSafetyUtil;
import org.genfork.grid.replication.tx.TxEnvelopeCodec;
import org.genfork.grid.replication.tx.TxEnvelopeCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LAZY / live apply of one stream of a multi-stream TX must not leave eternal ENVELOPE
 * membership that starves sealed dump (seal / huge-OpLog-without-{@code .sbpt} path).
 * Mid-TX ENVELOPE defer remains safety until closure completes or fail-closed aborts.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
class LazyMultiStreamEnvelopeOrphanSealIT {
	private static final String DOMAIN_A = "env_orphan_a";
	private static final String DOMAIN_B = "env_orphan_b";
	private static final long MAX_OPLOG_DELTA_WITHOUT_SBPT = 100_000L;
	private static final long PAD_SEQ = MAX_OPLOG_DELTA_WITHOUT_SBPT + 50L;
	private static final long TX_ID = 0x0e01L;
	private static final long SCHEMA_EPOCH = 1L;

	@TempDir
	Path tmp;

	@Test
	void liveApplyOneStreamThenLazyTouchClosesEnvelopeAndSealsHugeTip() {
		final GridConfigurationProperties props = new GridConfigurationProperties();
		props.getDurability().setEnabled(true);
		props.getDurability().setHydrateMode("LAZY");
		props.getDurability().setAdaptiveDiskFirst(false);
		props.getReplication().setEnabled(false);
		props.getReplication().getOpLog().setDataDir(tmp.resolve("repl").toString());
		props.getReplication().getOpLog().setFsync(false);
		props.getReplication().setClusterId("c-env-orphan");
		props.getReplication().setNodeId("n1");

		final ReplicationCoordinator coord = new ReplicationCoordinator(props);
		coord.start();
		try {
			final GridEntriesProcessor procA = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			final GridEntriesProcessor procB = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			coord.registerDomain(DOMAIN_A, shard -> shard == 0 ? procA : null, true);
			coord.registerDomain(DOMAIN_B, shard -> shard == 0 ? procB : null, true);

			final byte[] txKey = Long.toHexString(TX_ID).getBytes(StandardCharsets.UTF_8);
			final byte[] membership = TxEnvelopeCodec.encode(java.util.List.of(
					new TxEnvelopeCodec.StreamRef(DOMAIN_A, 0),
					new TxEnvelopeCodec.StreamRef(DOMAIN_B, 0)));
			assertNotNull(membership);

			final ReplicaApplier applierA = coord.applier(DOMAIN_A);
			assertNotNull(applierA);
			final TxEnvelopeCoordinator envelope = applierA.envelopeCoordinator();
			assertNotNull(envelope);

			// Live apply only stream A (COMMIT clears openTxId; membership stays open — RC orphan).
			applierA.apply(op(DOMAIN_A, 0, 1L, ReplicationOpType.TX_BEGIN, txKey, membership), false);
			applierA.apply(op(DOMAIN_A, 0, 2L, ReplicationOpType.UPSERT, bytes("ka"), bytes("va")), false);
			applierA.apply(op(DOMAIN_A, 0, 3L, ReplicationOpType.TX_COMMIT, txKey, null), false);

			assertFalse(applierA.hasOpenTx(DOMAIN_A, 0));
			assertTrue(envelope.hasOpenApplyMembershipForStream(DOMAIN_A, 0));
			assertTrue(envelope.hasOpenApplyMembershipForStream(DOMAIN_B, 0));
			assertEquals(
					SealedDumpSafetyUtil.DeferReason.ENVELOPE,
					SealedDumpSafetyUtil.deferReason(applierA, DOMAIN_A, 0));

			// Sibling stream complete path lives in OpLog for closure hydrate.
			append(coord, DOMAIN_B, 0, 1L, ReplicationOpType.TX_BEGIN, txKey, membership);
			append(coord, DOMAIN_B, 0, 2L, ReplicationOpType.UPSERT, bytes("kb"), bytes("vb"));
			append(coord, DOMAIN_B, 0, 3L, ReplicationOpType.TX_COMMIT, txKey, null);

			// Huge tip on A without .sbpt — LAZY touch must close orphan then at-risk dump (no ceiling raise).
			coord.getOpLog().append(op(DOMAIN_A, 0, PAD_SEQ, ReplicationOpType.UPSERT,
					bytes("pad-k"), bytes("pad-v")));

			assertDoesNotThrow(
					() -> coord.ensureShardHydrated(DOMAIN_A, 0),
					"closure + at-risk dump must clear huge-delta fence without raising ceiling");

			assertFalse(
					envelope.hasOpenApplyMembershipForStream(DOMAIN_A, 0),
					"LAZY hydrate must close orphan multi-stream membership (no eternal ENVELOPE)");
			assertFalse(
					envelope.hasOpenApplyMembershipForStream(DOMAIN_B, 0),
					"sibling stream membership must clear after closure");
			assertEquals(
					SealedDumpSafetyUtil.DeferReason.NONE,
					SealedDumpSafetyUtil.deferReason(applierA, DOMAIN_A, 0));
		} finally {
			coord.stop();
		}
	}

	@Test
	void orphanWithoutSiblingOpLogFailClosedAbortsSoDumpCanProceed() {
		final GridConfigurationProperties props = new GridConfigurationProperties();
		props.getDurability().setEnabled(true);
		props.getDurability().setHydrateMode("LAZY");
		props.getDurability().setAdaptiveDiskFirst(false);
		props.getReplication().setEnabled(false);
		props.getReplication().getOpLog().setDataDir(tmp.resolve("repl-abort").toString());
		props.getReplication().getOpLog().setFsync(false);
		props.getReplication().setClusterId("c-env-orphan-abort");
		props.getReplication().setNodeId("n1");

		final ReplicationCoordinator coord = new ReplicationCoordinator(props);
		coord.start();
		try {
			final GridEntriesProcessor procA = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			final GridEntriesProcessor procB = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			coord.registerDomain(DOMAIN_A, shard -> shard == 0 ? procA : null, true);
			coord.registerDomain(DOMAIN_B, shard -> shard == 0 ? procB : null, true);

			final byte[] txKey = Long.toHexString(TX_ID).getBytes(StandardCharsets.UTF_8);
			final byte[] membership = TxEnvelopeCodec.encode(java.util.List.of(
					new TxEnvelopeCodec.StreamRef(DOMAIN_A, 0),
					new TxEnvelopeCodec.StreamRef(DOMAIN_B, 0)));

			final ReplicaApplier applierA = coord.applier(DOMAIN_A);
			assertNotNull(applierA);
			final TxEnvelopeCoordinator envelope = applierA.envelopeCoordinator();
			assertNotNull(envelope);

			applierA.apply(op(DOMAIN_A, 0, 1L, ReplicationOpType.TX_BEGIN, txKey, membership), false);
			applierA.apply(op(DOMAIN_A, 0, 2L, ReplicationOpType.UPSERT, bytes("ka"), bytes("va")), false);
			applierA.apply(op(DOMAIN_A, 0, 3L, ReplicationOpType.TX_COMMIT, txKey, null), false);
			assertTrue(envelope.hasOpenApplyMembershipForStream(DOMAIN_A, 0));
			assertEquals(
					SealedDumpSafetyUtil.DeferReason.ENVELOPE,
					SealedDumpSafetyUtil.deferReason(applierA, DOMAIN_A, 0));

			// No sibling OpLog → prepareSeal fail-closed abort clears membership so dump can proceed.
			assertTrue(coord.dumpDomainSnapshot(DOMAIN_A) > 0);
			assertFalse(envelope.hasOpenApplyMembershipForStream(DOMAIN_A, 0));
			assertFalse(envelope.hasOpenApplyMembershipForStream(DOMAIN_B, 0));
			assertEquals(
					SealedDumpSafetyUtil.DeferReason.NONE,
					SealedDumpSafetyUtil.deferReason(applierA, DOMAIN_A, 0));
		} finally {
			coord.stop();
		}
	}

	@Test
	void stickyFullyCommittedMembershipClearedByDumpChoke() {
		final GridConfigurationProperties props = new GridConfigurationProperties();
		props.getDurability().setEnabled(true);
		props.getDurability().setHydrateMode("LAZY");
		props.getDurability().setAdaptiveDiskFirst(false);
		props.getReplication().setEnabled(false);
		props.getReplication().getOpLog().setDataDir(tmp.resolve("repl-sticky").toString());
		props.getReplication().getOpLog().setFsync(false);
		props.getReplication().setClusterId("c-env-sticky");
		props.getReplication().setNodeId("n1");

		final ReplicationCoordinator coord = new ReplicationCoordinator(props);
		coord.start();
		try {
			final GridEntriesProcessor procA = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			final GridEntriesProcessor procB = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			coord.registerDomain(DOMAIN_A, shard -> shard == 0 ? procA : null, true);
			coord.registerDomain(DOMAIN_B, shard -> shard == 0 ? procB : null, true);

			final byte[] membership = TxEnvelopeCodec.encode(java.util.List.of(
					new TxEnvelopeCodec.StreamRef(DOMAIN_A, 0),
					new TxEnvelopeCodec.StreamRef(DOMAIN_B, 0)));
			final ReplicaApplier applierA = coord.applier(DOMAIN_A);
			assertNotNull(applierA);
			final TxEnvelopeCoordinator envelope = applierA.envelopeCoordinator();
			assertNotNull(envelope);

			// Fully-committed leftover still in applyOpen (missed take) — sticky ENVELOPE.
			envelope.registerApply(TxEnvelopeCodec.decode(TX_ID, membership));
			assertFalse(envelope.noteApplyCommit(TX_ID, new TxEnvelopeCodec.StreamRef(DOMAIN_A, 0)));
			assertTrue(envelope.noteApplyCommit(TX_ID, new TxEnvelopeCodec.StreamRef(DOMAIN_B, 0)));
			assertTrue(envelope.isApplyFullyCommitted(TX_ID));
			assertTrue(envelope.hasOpenApplyMembershipForStream(DOMAIN_A, 0));
			assertEquals(
					SealedDumpSafetyUtil.DeferReason.ENVELOPE,
					SealedDumpSafetyUtil.deferReason(applierA, DOMAIN_A, 0));

			coord.getOpLog().append(op(DOMAIN_A, 0, 1L, ReplicationOpType.UPSERT, bytes("sk"), bytes("sv")));

			assertTrue(coord.dumpDomainSnapshot(DOMAIN_A) > 0,
					"prepareSeal must clear sticky so gated dump can write rows");
			assertFalse(envelope.hasOpenApplyMembershipForStream(DOMAIN_A, 0));
			assertEquals(
					SealedDumpSafetyUtil.DeferReason.NONE,
					SealedDumpSafetyUtil.deferReason(applierA, DOMAIN_A, 0));
		} finally {
			coord.stop();
		}
	}

	@Test
	void postMemoOrphanClearedByDumpWithoutRestart() {
		final GridConfigurationProperties props = new GridConfigurationProperties();
		props.getDurability().setEnabled(true);
		props.getDurability().setHydrateMode("LAZY");
		props.getDurability().setAdaptiveDiskFirst(false);
		props.getReplication().setEnabled(false);
		props.getReplication().getOpLog().setDataDir(tmp.resolve("repl-post-memo").toString());
		props.getReplication().getOpLog().setFsync(false);
		props.getReplication().setClusterId("c-env-post-memo");
		props.getReplication().setNodeId("n1");

		final ReplicationCoordinator coord = new ReplicationCoordinator(props);
		coord.start();
		try {
			final GridEntriesProcessor procA = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			final GridEntriesProcessor procB = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			coord.registerDomain(DOMAIN_A, shard -> shard == 0 ? procA : null, true);
			coord.registerDomain(DOMAIN_B, shard -> shard == 0 ? procB : null, true);

			append(coord, DOMAIN_A, 0, 1L, ReplicationOpType.UPSERT, bytes("a0"), bytes("v0"));
			append(coord, DOMAIN_B, 0, 1L, ReplicationOpType.UPSERT, bytes("b0"), bytes("v0"));
			coord.ensureShardHydrated(DOMAIN_A, 0);
			coord.ensureShardHydrated(DOMAIN_B, 0);

			final byte[] txKey = Long.toHexString(TX_ID + 1L).getBytes(StandardCharsets.UTF_8);
			final byte[] membership = TxEnvelopeCodec.encode(java.util.List.of(
					new TxEnvelopeCodec.StreamRef(DOMAIN_A, 0),
					new TxEnvelopeCodec.StreamRef(DOMAIN_B, 0)));
			final ReplicaApplier applierA = coord.applier(DOMAIN_A);
			assertNotNull(applierA);
			final TxEnvelopeCoordinator envelope = applierA.envelopeCoordinator();
			assertNotNull(envelope);

			applierA.apply(op(DOMAIN_A, 0, 2L, ReplicationOpType.TX_BEGIN, txKey, membership), false);
			applierA.apply(op(DOMAIN_A, 0, 3L, ReplicationOpType.UPSERT, bytes("ka"), bytes("va")), false);
			applierA.apply(op(DOMAIN_A, 0, 4L, ReplicationOpType.TX_COMMIT, txKey, null), false);
			assertTrue(envelope.hasOpenApplyMembershipForStream(DOMAIN_A, 0));

			append(coord, DOMAIN_B, 0, 2L, ReplicationOpType.TX_BEGIN, txKey, membership);
			append(coord, DOMAIN_B, 0, 3L, ReplicationOpType.UPSERT, bytes("kb"), bytes("vb"));
			append(coord, DOMAIN_B, 0, 4L, ReplicationOpType.TX_COMMIT, txKey, null);
			coord.getOpLog().append(op(DOMAIN_A, 0, PAD_SEQ, ReplicationOpType.UPSERT,
					bytes("pad-k"), bytes("pad-v")));

			// Memo already set — choke dump (periodic/at-risk path) must close orphan without restart.
			assertTrue(coord.dumpDomainSnapshot(DOMAIN_A) > 0);
			assertFalse(envelope.hasOpenApplyMembershipForStream(DOMAIN_A, 0));
			assertFalse(envelope.hasOpenApplyMembershipForStream(DOMAIN_B, 0));
			assertEquals(
					SealedDumpSafetyUtil.DeferReason.NONE,
					SealedDumpSafetyUtil.deferReason(applierA, DOMAIN_A, 0));
		} finally {
			coord.stop();
		}
	}

	@Test
	void midTxMultiStreamDumpChokeStillDefersWithoutAborting() {
		final GridConfigurationProperties props = new GridConfigurationProperties();
		props.getDurability().setEnabled(true);
		props.getDurability().setHydrateMode("LAZY");
		props.getDurability().setAdaptiveDiskFirst(false);
		props.getReplication().setEnabled(false);
		props.getReplication().getOpLog().setDataDir(tmp.resolve("repl-mid-tx").toString());
		props.getReplication().getOpLog().setFsync(false);
		props.getReplication().setClusterId("c-env-mid-tx");
		props.getReplication().setNodeId("n1");

		final ReplicationCoordinator coord = new ReplicationCoordinator(props);
		coord.start();
		try {
			final GridEntriesProcessor procA = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			final GridEntriesProcessor procB = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
			coord.registerDomain(DOMAIN_A, shard -> shard == 0 ? procA : null, true);
			coord.registerDomain(DOMAIN_B, shard -> shard == 0 ? procB : null, true);

			final byte[] txKey = Long.toHexString(TX_ID + 2L).getBytes(StandardCharsets.UTF_8);
			final byte[] membership = TxEnvelopeCodec.encode(java.util.List.of(
					new TxEnvelopeCodec.StreamRef(DOMAIN_A, 0),
					new TxEnvelopeCodec.StreamRef(DOMAIN_B, 0)));
			final ReplicaApplier applierA = coord.applier(DOMAIN_A);
			assertNotNull(applierA);
			final TxEnvelopeCoordinator envelope = applierA.envelopeCoordinator();
			assertNotNull(envelope);

			applierA.apply(op(DOMAIN_A, 0, 1L, ReplicationOpType.TX_BEGIN, txKey, membership), false);
			applierA.apply(op(DOMAIN_A, 0, 2L, ReplicationOpType.UPSERT, bytes("ka"), bytes("va")), false);
			assertTrue(applierA.hasOpenTx(DOMAIN_A, 0));
			assertTrue(envelope.hasOpenApplyMembershipForStream(DOMAIN_A, 0));
			assertEquals(
					SealedDumpSafetyUtil.DeferReason.OPEN_TX,
					SealedDumpSafetyUtil.deferReason(applierA, DOMAIN_A, 0));

			// Periodic/at-risk choke must defer mid-TX — never abort live open TX / membership.
			assertEquals(0, coord.dumpDomainSnapshot(DOMAIN_A),
					"mid-TX OPEN_TX must defer gated dump");
			assertTrue(applierA.hasOpenTx(DOMAIN_A, 0));
			assertTrue(envelope.hasOpenApplyMembershipForStream(DOMAIN_A, 0),
					"mid-TX membership must not be abort-cleared by prepareSeal");
			assertEquals(
					SealedDumpSafetyUtil.DeferReason.OPEN_TX,
					SealedDumpSafetyUtil.deferReason(applierA, DOMAIN_A, 0));
		} finally {
			coord.stop();
		}
	}

	private static void append(
			ReplicationCoordinator coord,
			String domain,
			int shard,
			long seq,
			ReplicationOpType type,
			byte[] key,
			byte[] value
	) {
		coord.getOpLog().append(op(domain, shard, seq, type, key, value));
	}

	private static ReplicationOp op(
			String domain,
			int shard,
			long seq,
			ReplicationOpType type,
			byte[] key,
			byte[] value
	) {
		return OpLogCodec.withChecksum(
				new ReplicationOp(domain, shard, seq, type, key, value, SCHEMA_EPOCH, 0L));
	}

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}
}
