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

import org.genfork.grid.nio.EncodeBuffers;
import org.genfork.grid.replication.netty.ReplicationRpcCodec;
import org.genfork.grid.replication.netty.ReplicationRpcCodec.ApplyAck;
import org.genfork.grid.replication.netty.ReplicationRpcCodec.Hello;
import org.genfork.grid.replication.orchid.OrchidTransport.OrchidPhaseBatchMessage;
import org.genfork.grid.replication.orchid.OrchidTransport.OrchidPhaseDigest;
import org.genfork.grid.replication.orchid.OrchidTransport.OrchidPhaseMessage;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class ReplicationRpcCodecTest {
	@Test
	void helloRoundTrip() {
		final Hello hello = new Hello("n1", "c1", "dc-a", 7L, 5L, (byte) 1);
		final Hello decoded = ReplicationRpcCodec.decodeHello(ReplicationRpcCodec.encodeHello(hello));
		assertEquals("n1", decoded.nodeId());
		assertEquals("c1", decoded.clusterId());
		assertEquals("dc-a", decoded.localDc());
		assertEquals(7L, decoded.schemaEpoch());
		assertEquals(5L, decoded.regionEpoch());
		assertEquals((byte) 1, decoded.regionRole());
	}

	@Test
	void helloLegacyBodyDefaultsRegionFence() {
		final byte[] legacy = encodeLegacyHello("n1", "c1", "dc-a", 3L);
		final Hello decoded = ReplicationRpcCodec.decodeHello(legacy);
		assertEquals(3L, decoded.schemaEpoch());
		assertEquals(0L, decoded.regionEpoch());
		assertEquals((byte) 0, decoded.regionRole());
	}

	@Test
	void orchidPhaseRoundTrip() {
		final OrchidPhaseMessage msg = new OrchidPhaseMessage("n1", 1.5, 2.0, 9L, 3L, 99L);
		final OrchidPhaseMessage decoded = ReplicationRpcCodec.decodePhase(ReplicationRpcCodec.encodePhase(msg));
		assertEquals("n1", decoded.nodeId());
		assertEquals(1.5, decoded.phase(), 1e-9);
		assertEquals(9L, decoded.lastCommittedSeq());
		assertEquals(99L, decoded.digest());
	}

	@Test
	void orchidPhaseEncodeIntoMatchesEncode() {
		final OrchidPhaseMessage msg = new OrchidPhaseMessage("n2", 0.25, 1.0, 4L, 8L, 42L);
		final byte[] expected = ReplicationRpcCodec.encodePhase(msg);
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(expected.length);
		ReplicationRpcCodec.encodePhaseInto(buf, msg);
		assertArrayEquals(expected, EncodeBuffers.toByteArray(buf));
	}

	@Test
	void orchidPhaseMessageBatchRoundTrip() {
		final List<OrchidPhaseMessage> messages = List.of(
				new OrchidPhaseMessage("n1", 1.1, 0.5, 10L, 1L, 11L),
				new OrchidPhaseMessage("n1", 1.1, 0.5, 10L, 2L, 22L),
				new OrchidPhaseMessage("n2", 0.3, 0.9, 4L, 7L, 77L));
		final byte[] encoded = ReplicationRpcCodec.encodePhaseMessageBatch(messages);
		final List<OrchidPhaseMessage> decoded = ReplicationRpcCodec.decodePhaseMessageBatch(encoded);
		assertEquals(3, decoded.size());
		assertEquals("n1", decoded.get(0).nodeId());
		assertEquals(1L, decoded.get(0).proposeId());
		assertEquals(11L, decoded.get(0).digest());
		assertEquals(2L, decoded.get(1).proposeId());
		assertEquals(22L, decoded.get(1).digest());
		assertEquals("n2", decoded.get(2).nodeId());
		assertEquals(77L, decoded.get(2).digest());
	}

	@Test
	void orchidPhaseBatchReusesSinglePhaseLayout() {
		final OrchidPhaseMessage one = new OrchidPhaseMessage("n1", 1.1, 0.5, 10L, 1L, 11L);
		final OrchidPhaseMessage two = new OrchidPhaseMessage("n1", 1.1, 0.5, 10L, 2L, 22L);
		final byte[] batch = ReplicationRpcCodec.encodePhaseMessageBatch(List.of(one, two));
		final ByteBuffer buf = EncodeBuffers.wrapLe(batch);
		assertEquals(2, buf.getInt());
		assertArrayEquals(ReplicationRpcCodec.encodePhase(one), sliceRemainingPhase(buf));
		assertArrayEquals(ReplicationRpcCodec.encodePhase(two), sliceRemainingPhase(buf));
	}

	@Test
	void orchidPhaseBatchFromDigestsRoundTrip() {
		final OrchidPhaseBatchMessage logical = new OrchidPhaseBatchMessage(
				"n1", 1.1, 0.5, 10L,
				List.of(new OrchidPhaseDigest(1L, 11L), new OrchidPhaseDigest(2L, 22L)));
		final List<OrchidPhaseMessage> decoded =
				ReplicationRpcCodec.decodePhaseMessageBatch(ReplicationRpcCodec.encodePhaseBatch(logical));
		assertEquals(2, decoded.size());
		assertEquals("n1", decoded.get(0).nodeId());
		assertEquals(1L, decoded.get(0).proposeId());
		assertEquals(22L, decoded.get(1).digest());
		assertEquals(10L, decoded.get(1).lastCommittedSeq());
	}

	@Test
	void applyAckBatchRoundTrip() {
		final List<ApplyAck> acks = List.of(
				new ApplyAck("n1", "orders", 0, 5L),
				new ApplyAck("n1", "orders", 1, 6L),
				new ApplyAck("n1", "catalog", 0, 7L));
		final List<ApplyAck> decoded =
				ReplicationRpcCodec.decodeApplyAckBatch(ReplicationRpcCodec.encodeApplyAckBatch("n1", acks));
		assertEquals(3, decoded.size());
		assertEquals("orders", decoded.get(0).domainType());
		assertEquals(1, decoded.get(1).shard());
		assertEquals(7L, decoded.get(2).opSeq());
		assertEquals("n1", decoded.get(2).fromNodeId());
	}

	/** Consume one self-delimiting phase payload from {@code buf} (same layout as encodePhase). */
	private static byte[] sliceRemainingPhase(ByteBuffer buf) {
		final int mark = buf.position();
		final int idLen = buf.getInt();
		buf.position(mark);
		final int phaseBytes = Integer.BYTES + idLen
				+ Double.BYTES + Double.BYTES + Long.BYTES + Long.BYTES + Long.BYTES;
		final byte[] out = new byte[phaseBytes];
		buf.get(out);
		return out;
	}

	/** Pre-region HELLO body: nodeId, clusterId, localDc, schemaEpoch only. */
	private static byte[] encodeLegacyHello(String nodeId, String clusterId, String localDc, long schemaEpoch) {
		final byte[] a = nodeId.getBytes(StandardCharsets.UTF_8);
		final byte[] b = clusterId.getBytes(StandardCharsets.UTF_8);
		final byte[] c = localDc.getBytes(StandardCharsets.UTF_8);
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(
				Integer.BYTES + a.length + Integer.BYTES + b.length + Integer.BYTES + c.length + Long.BYTES);
		buf.putInt(a.length);
		buf.put(a);
		buf.putInt(b.length);
		buf.put(b);
		buf.putInt(c.length);
		buf.put(c);
		buf.putLong(schemaEpoch);
		return EncodeBuffers.toByteArray(buf);
	}
}
