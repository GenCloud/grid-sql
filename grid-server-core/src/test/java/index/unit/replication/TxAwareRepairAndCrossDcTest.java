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

import org.genfork.grid.replication.ReplicationNodeState;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.crossdc.CrossDcMode;
import org.genfork.grid.replication.crossdc.CrossDcPublisher;
import org.genfork.grid.replication.repair.HomologousRepair;
import org.genfork.grid.replication.repair.VersionLocus;
import org.genfork.grid.replication.transport.ReplicationPeer;
import org.genfork.grid.replication.transport.ReplicationPublisher;
import org.genfork.grid.replication.tx.OpLogTxUnits;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static index.unit.replication.TxEnvelopeOpFixtures.marker;
import static index.unit.replication.TxEnvelopeOpFixtures.op;
import static index.unit.replication.TxEnvelopeOpFixtures.upsert;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TX-aware HomologousRepair / CrossDcPublisher / OpLogTxUnits.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class TxAwareRepairAndCrossDcTest {

	@Test
	void homologousRepairBuffersMidTxUntilCommit() {
		final HomologousRepair repair = new HomologousRepair();
		repair.observe(marker("d", 0, 1L, ReplicationOpType.TX_BEGIN));
		assertTrue(repair.hasOpenTx("d", 0));

		repair.observe(upsert("d", 0, 2L, new byte[]{1}, new byte[]{9}));
		assertTrue(repair.getLocusMap().snapshot("d", 0).isEmpty(),
				"mid-TX UPSERT must not publish locus truth");

		final Map<Long, VersionLocus> remote = new HashMap<>();
		remote.put(1L, new VersionLocus("d", 0, 1L, 9L, 1L, 1L));
		assertTrue(repair.reconcile("d", 0, remote).isEmpty(),
				"reconcile fail-closed while open TX");

		repair.observe(marker("d", 0, 3L, ReplicationOpType.TX_COMMIT));
		assertFalse(repair.hasOpenTx("d", 0));
		assertFalse(repair.getLocusMap().snapshot("d", 0).isEmpty());
	}

	@Test
	void homologousRepairDiscardsOnAbort() {
		final HomologousRepair repair = new HomologousRepair();
		repair.observe(marker("d", 0, 1L, ReplicationOpType.TX_BEGIN));
		repair.observe(upsert("d", 0, 2L, new byte[]{1}, new byte[]{9}));
		repair.observe(marker("d", 0, 3L, ReplicationOpType.TX_ABORT));
		assertFalse(repair.hasOpenTx("d", 0));
		assertTrue(repair.getLocusMap().snapshot("d", 0).isEmpty());
	}

	@Test
	void crossDcHoldsOpenTxUntilCommit() {
		final ReplicationNodeState state = new ReplicationNodeState("n1", "c", "dc-a", 1L);
		final CrossDcPublisher publisher = new CrossDcPublisher(
				state,
				CrossDcMode.ASYNC_SHIP,
				100,
				60_000L,
				false,
				1_000L,
				List.of(),
				List.of()
		);
		publisher.setRemotePeers(List.of(new ReplicationPeer("r1", "127.0.0.1", 1, "dc-b")));
		final AtomicInteger ships = new AtomicInteger();
		publisher.setShipper((peer, segment) -> ships.incrementAndGet());

		publisher.onAppended(marker("d", 0, 1L, ReplicationOpType.TX_BEGIN));
		publisher.onAppended(upsert("d", 0, 2L, new byte[]{1}, new byte[]{2}));
		assertEquals(0, ships.get(), "must not ship mid-unit");
		assertTrue(publisher.bufferedOpCount("d", 0) >= 2);

		publisher.onAppended(marker("d", 0, 3L, ReplicationOpType.TX_COMMIT));
		assertEquals(1, ships.get());
		assertEquals(0, publisher.bufferedOpCount("d", 0));
	}

	@Test
	void crossDcHoldsMultiShardEnvelopeUntilAllShardsCommit() {
		final ReplicationNodeState state = new ReplicationNodeState("n1", "c", "dc-a", 1L);
		final CrossDcPublisher publisher = new CrossDcPublisher(
				state,
				CrossDcMode.ASYNC_SHIP,
				100,
				60_000L,
				false,
				1_000L,
				List.of(),
				List.of()
		);
		publisher.setRemotePeers(List.of(new ReplicationPeer("r1", "127.0.0.1", 1, "dc-b")));
		final AtomicInteger ships = new AtomicInteger();
		publisher.setShipper((peer, segment) -> ships.incrementAndGet());

		final long txId = 0x2aL;
		final byte[] env = org.genfork.grid.replication.tx.TxEnvelopeCodec.encode(List.of(
				new org.genfork.grid.replication.tx.TxEnvelopeCodec.StreamRef("d", 0),
				new org.genfork.grid.replication.tx.TxEnvelopeCodec.StreamRef("d", 1)
		));
		final byte[] key = Long.toHexString(txId).getBytes(java.nio.charset.StandardCharsets.UTF_8);

		publisher.onAppended(op("d", 0, 1L, ReplicationOpType.TX_BEGIN, key, env));
		publisher.onAppended(upsert("d", 0, 2L, new byte[]{10}, new byte[]{1}));
		publisher.onAppended(op("d", 0, 3L, ReplicationOpType.TX_COMMIT, key, null));
		assertEquals(0, ships.get(), "must not ship until envelope complete");
		assertTrue(publisher.getEnvelopeCoordinator().heldShipOpCount(txId) >= 3);

		publisher.onAppended(op("d", 1, 4L, ReplicationOpType.TX_BEGIN, key, env));
		publisher.onAppended(upsert("d", 1, 5L, new byte[]{11}, new byte[]{2}));
		publisher.onAppended(op("d", 1, 6L, ReplicationOpType.TX_COMMIT, key, null));
		assertEquals(2, ships.get(), "one segment per stream after envelope commit");
		assertEquals(0, publisher.getEnvelopeCoordinator().heldShipOpCount(txId));
	}

	@Test
	void opLogTxUnitsTrimIncompleteSuffix() {
		final List<ReplicationOp> ops = List.of(
				upsert("d", 0, 1L, new byte[]{1}, new byte[]{1}),
				marker("d", 0, 2L, ReplicationOpType.TX_BEGIN),
				upsert("d", 0, 3L, new byte[]{2}, new byte[]{2})
		);
		final List<ReplicationOp> trimmed = OpLogTxUnits.trimToCompleteUnits(ops);
		assertEquals(1, trimmed.size());
		assertEquals(1L, trimmed.getFirst().opSeq());
		assertTrue(OpLogTxUnits.hasIncompleteOpenTx(ops));
		assertTrue(OpLogTxUnits.isSeqInCompleteUnit(ops, 1L));
		assertFalse(OpLogTxUnits.isSeqInCompleteUnit(ops, 3L));
	}

	@Test
	void sameDcPublisherHoldsOpenTxUntilCommit() {
		final ReplicationNodeState state = new ReplicationNodeState("n1", "c", "dc-a", 1L);
		final org.genfork.grid.replication.flow.ReplicationFlowControl flow =
				new org.genfork.grid.replication.flow.ReplicationFlowControl(1024, List.of());
		final ReplicationPublisher publisher = new ReplicationPublisher(state, null, flow, 100);
		publisher.setPeers(List.of(new ReplicationPeer("p1", "127.0.0.1", 1, "dc-a")));
		final AtomicInteger ships = new AtomicInteger();
		publisher.setShipper((peer, segment) -> ships.incrementAndGet());

		publisher.onAppended(marker("d", 0, 1L, ReplicationOpType.TX_BEGIN));
		publisher.onAppended(upsert("d", 0, 2L, new byte[]{1}, new byte[]{2}));
		publisher.flushAll();
		assertEquals(0, ships.get(), "must not ship mid-unit same-DC");
		assertTrue(publisher.bufferedOpCount("d", 0) >= 2);

		publisher.onAppended(marker("d", 0, 3L, ReplicationOpType.TX_COMMIT));
		awaitShips(ships, 1);
		assertEquals(1, ships.get());
		assertEquals(0, publisher.bufferedOpCount("d", 0));
	}

	private static void awaitShips(AtomicInteger ships, int expected) {
		final long deadline = System.currentTimeMillis() + 2_000L;
		while (System.currentTimeMillis() < deadline && ships.get() < expected) {
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10L));
		}
	}
}