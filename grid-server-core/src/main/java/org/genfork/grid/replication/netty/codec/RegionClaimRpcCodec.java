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
package org.genfork.grid.replication.netty.codec;

import org.genfork.grid.nio.EncodeBuffers;
import org.genfork.grid.replication.netty.ReplicationRpcCodec.RegionClaimAck;
import org.genfork.grid.replication.netty.ReplicationRpcCodec.RegionClaimReq;
import org.genfork.grid.replication.transport.ReplicationMessageType;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * {@link ReplicationMessageType#REGION_CLAIM_REQ} / {@link ReplicationMessageType#REGION_CLAIM_ACK}
 * encode/decode.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class RegionClaimRpcCodec {
	private RegionClaimRpcCodec() {
	}

	public static byte[] encodeRegionClaimReq(
			long claimId,
			long proposedEpoch,
			String fromNodeId,
			String fromDc,
			byte claimantRoleWire
	) {
		Objects.requireNonNull(fromNodeId, "fromNodeId");
		final byte[] from = fromNodeId.getBytes(StandardCharsets.UTF_8);
		final byte[] dc = (fromDc == null ? "" : fromDc).getBytes(StandardCharsets.UTF_8);
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(
				Long.BYTES + Long.BYTES + Integer.BYTES + from.length + Integer.BYTES + dc.length + Byte.BYTES);
		buf.putLong(claimId);
		buf.putLong(proposedEpoch);
		EncodeBuffers.putLengthPrefixed(buf, from);
		EncodeBuffers.putLengthPrefixed(buf, dc);
		buf.put(claimantRoleWire);
		return EncodeBuffers.toByteArray(buf);
	}

	public static RegionClaimReq decodeRegionClaimReq(byte[] body) {
		final ByteBuffer buf = EncodeBuffers.wrapLe(body);
		final long claimId = buf.getLong();
		final long proposedEpoch = buf.getLong();
		final String fromNodeId = EncodeBuffers.getUtf8(buf);
		final String fromDc = EncodeBuffers.getUtf8(buf);
		final byte role = buf.get();
		return new RegionClaimReq(claimId, proposedEpoch, fromNodeId, fromDc, role);
	}

	public static byte[] encodeRegionClaimAck(long claimId, String fromNodeId, byte voterRoleWire) {
		Objects.requireNonNull(fromNodeId, "fromNodeId");
		final byte[] from = fromNodeId.getBytes(StandardCharsets.UTF_8);
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(Long.BYTES + Integer.BYTES + from.length + Byte.BYTES);
		buf.putLong(claimId);
		EncodeBuffers.putLengthPrefixed(buf, from);
		buf.put(voterRoleWire);
		return EncodeBuffers.toByteArray(buf);
	}

	public static RegionClaimAck decodeRegionClaimAck(byte[] body) {
		final ByteBuffer buf = EncodeBuffers.wrapLe(body);
		final long claimId = buf.getLong();
		final String fromNodeId = EncodeBuffers.getUtf8(buf);
		final byte role = buf.get();
		return new RegionClaimAck(claimId, fromNodeId, role);
	}
}
