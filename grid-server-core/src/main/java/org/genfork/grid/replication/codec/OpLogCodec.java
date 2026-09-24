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
package org.genfork.grid.replication.codec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

import org.genfork.grid.replication.offheap.OffHeapBuffer;
import org.genfork.grid.utils.ArrayVectors;
import org.genfork.grid.utils.UnsafeMemory;
/**
 * Binary codec for {@link ReplicationOp} / {@link OpLogSegment} using off-heap scratch buffers.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class OpLogCodec {
	private OpLogCodec() {
	}

	public static long checksumOf(ReplicationOp op) {
		final CRC32 crc = new CRC32();
		ArrayVectors.updateCrc32(crc, op.domainType().getBytes(StandardCharsets.UTF_8));
		ArrayVectors.updateCrc32(crc, intBytes(op.shard()));
		ArrayVectors.updateCrc32(crc, longBytes(op.opSeq()));
		crc.update(op.type().code());
		ArrayVectors.updateCrc32(crc, op.key());
		if (op.value() != null) {
			ArrayVectors.updateCrc32(crc, op.value());
		}
		ArrayVectors.updateCrc32(crc, longBytes(op.schemaEpoch()));
		return crc.getValue();
	}

	public static ReplicationOp withChecksum(ReplicationOp op) {
		return new ReplicationOp(
				op.domainType(),
				op.shard(),
				op.opSeq(),
				op.type(),
				op.key(),
				op.value(),
				op.schemaEpoch(),
				checksumOf(op)
		);
	}

	public static int encodedOpSize(ReplicationOp op) {
		final byte[] domain = op.domainType().getBytes(StandardCharsets.UTF_8);
		final byte[] key = op.key();
		final byte[] value = op.value();
		final int valueLen = value == null ? 0 : value.length;
		return 4 + domain.length + 4 + 8 + 1 + 4 + key.length + 4
				+ (value == null ? 0 : valueLen) + 8 + 8;
	}

	/**
	 * Encodes into a freshly allocated off-heap buffer. Caller must {@link OffHeapBuffer#close()}.
	 */
	public static OffHeapBuffer encodeOpOffHeap(ReplicationOp op) {
		final byte[] domain = op.domainType().getBytes(StandardCharsets.UTF_8);
		final byte[] key = op.key();
		final byte[] value = op.value();
		final int valueLen = value == null ? -1 : value.length;
		final OffHeapBuffer buf = OffHeapBuffer.allocate(encodedOpSize(op));
		putBytes(buf, domain);
		buf.putInt(op.shard());
		buf.putLong(op.opSeq());
		buf.putByte(op.type().code());
		putBytes(buf, key);
		buf.putInt(valueLen);
		if (value != null) {
			buf.putBytes(value);
		}
		buf.putLong(op.schemaEpoch());
		buf.putLong(op.checksum());
		return buf;
	}

	public static byte[] encodeOp(ReplicationOp op) {
		try (OffHeapBuffer buf = encodeOpOffHeap(op)) {
			final byte[] heap = new byte[(int) buf.position()];
			UnsafeMemory.copy(null, buf.address(), heap, UnsafeMemory.ARRAY_BASE_OFFSET, heap.length);
			return heap;
		}
	}

	public static ReplicationOp decodeOp(byte[] bytes) {
		try (OffHeapBuffer buf = OffHeapBuffer.allocate(bytes.length)) {
			buf.putBytes(bytes);
			buf.position(0);
			return decodeOp(buf);
		}
	}

	public static ReplicationOp decodeOp(OffHeapBuffer buf) {
		final String domain = new String(getBytes(buf), StandardCharsets.UTF_8);
		final int shard = buf.getInt();
		final long opSeq = buf.getLong();
		final ReplicationOpType type = ReplicationOpType.fromCode(buf.getByte());
		final byte[] key = getBytes(buf);
		final int valueLen = buf.getInt();
		final byte[] value = valueLen < 0 ? null : buf.getBytes(valueLen);
		final long schemaEpoch = buf.getLong();
		final long checksum = buf.getLong();
		final long expected = checksumOf(new ReplicationOp(domain, shard, opSeq, type, key, value, schemaEpoch, 0));
		if (expected != checksum) {
			throw new IllegalStateException("ReplicationOp checksum mismatch seq=" + opSeq);
		}
		return new ReplicationOp(domain, shard, opSeq, type, key, value, schemaEpoch, checksum);
	}

	public static long segmentChecksum(List<ReplicationOp> ops) {
		final CRC32 crc = new CRC32();
		for (ReplicationOp op : ops) {
			try (OffHeapBuffer encoded = encodeOpOffHeap(op)) {
				final byte[] tmp = new byte[(int) encoded.position()];
				UnsafeMemory.copy(null, encoded.address(), tmp, UnsafeMemory.ARRAY_BASE_OFFSET, tmp.length);
				crc.update(tmp);
			}
		}
		return crc.getValue();
	}

	public static OffHeapBuffer encodeSegmentOffHeap(OpLogSegment segment) {
		int opsBytes = 0;
		final List<OffHeapBuffer> encodedOps = new ArrayList<>(segment.ops().size());
		try {
			for (ReplicationOp op : segment.ops()) {
				final OffHeapBuffer encoded = encodeOpOffHeap(op);
				encodedOps.add(encoded);
				opsBytes += 4 + (int) encoded.position();
			}
			final byte[] domain = segment.domainType().getBytes(StandardCharsets.UTF_8);
			final int capacity = 4 + domain.length + 4 + 8 + 8 + 4 + opsBytes + 8;
			final OffHeapBuffer buf = OffHeapBuffer.allocate(capacity);
			putBytes(buf, domain);
			buf.putInt(segment.shard());
			buf.putLong(segment.fromSeq());
			buf.putLong(segment.toSeq());
			buf.putInt(encodedOps.size());
			for (OffHeapBuffer encoded : encodedOps) {
				buf.putInt((int) encoded.position());
				buf.putBytes(encoded.address(), (int) encoded.position());
			}
			buf.putLong(segment.segmentChecksum());
			return buf;
		} finally {
			for (OffHeapBuffer encoded : encodedOps) {
				encoded.close();
			}
		}
	}

	public static byte[] encodeSegment(OpLogSegment segment) {
		try (OffHeapBuffer buf = encodeSegmentOffHeap(segment)) {
			final byte[] heap = new byte[(int) buf.position()];
			UnsafeMemory.copy(null, buf.address(), heap, UnsafeMemory.ARRAY_BASE_OFFSET, heap.length);
			return heap;
		}
	}

	public static OpLogSegment decodeSegment(byte[] bytes) {
		try (OffHeapBuffer buf = OffHeapBuffer.allocate(bytes.length)) {
			buf.putBytes(bytes);
			buf.position(0);
			return decodeSegment(buf);
		}
	}

	public static OpLogSegment decodeSegment(OffHeapBuffer buf) {
		final String domain = new String(getBytes(buf), StandardCharsets.UTF_8);
		final int shard = buf.getInt();
		final long fromSeq = buf.getLong();
		final long toSeq = buf.getLong();
		final int count = buf.getInt();
		final List<ReplicationOp> ops = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			final int len = buf.getInt();
			final long start = buf.position();
			final OffHeapBuffer view = OffHeapBuffer.wrapMappedAddress(buf.address() + start, len, null);
			ops.add(decodeOp(view));
			buf.position(start + len);
		}
		final long checksum = buf.getLong();
		final long expected = segmentChecksum(ops);
		if (expected != checksum) {
			throw new IllegalStateException("OpLogSegment checksum mismatch");
		}
		return new OpLogSegment(domain, shard, fromSeq, toSeq, ops, checksum);
	}

	private static void putBytes(OffHeapBuffer buf, byte[] data) {
		buf.putInt(data.length);
		buf.putBytes(data);
	}

	private static byte[] getBytes(OffHeapBuffer buf) {
		return buf.getBytes(buf.getInt());
	}

	private static byte[] intBytes(int v) {
		return new byte[]{
				(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v
		};
	}

	private static byte[] longBytes(long v) {
		return new byte[]{
				(byte) (v >>> 56), (byte) (v >>> 48), (byte) (v >>> 40), (byte) (v >>> 32),
				(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v
		};
	}
}
