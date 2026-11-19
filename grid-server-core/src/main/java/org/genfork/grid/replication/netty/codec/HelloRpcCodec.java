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
import org.genfork.grid.replication.netty.ReplicationRpcCodec.Hello;
import org.genfork.grid.replication.util.SealedShardInventoryEntry;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * HELLO handshake encode/decode (identity + duplex schema + Multi-DC region + catalog applied).
 * <p>
 * Optional trailing sealed shard inventory after aggregate sealed fingerprint (legacy peers omit).
 * Optional privilege fingerprint after inventory (legacy peers omit → {@code 0}).
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class HelloRpcCodec {
	/** Trailing Multi-DC region fence after {@code schemaEpoch} on HELLO (optional for legacy peers). */
	private static final int HELLO_REGION_EPOCH_BYTES = Long.BYTES;
	private static final int HELLO_REGION_ROLE_BYTES = Byte.BYTES;
	/** Trailing {@code _catalog} applied watermark after region role (optional). */
	private static final int HELLO_CATALOG_APPLIED_BYTES = Long.BYTES;
	/** Trailing catalog object-set fingerprint after applied watermark (optional). */
	private static final int HELLO_CATALOG_FINGERPRINT_BYTES = Long.BYTES;
	/** Trailing sealed artifacts name+size identity after catalog fingerprint (optional). */
	private static final int HELLO_SEALED_ARTIFACTS_FINGERPRINT_BYTES = Long.BYTES;
	/** Inventory entry count after sealed aggregate fingerprint (optional). */
	private static final int HELLO_SEALED_INVENTORY_COUNT_BYTES = Integer.BYTES;
	/** Trailing privilege fingerprint after sealed inventory (optional). */
	private static final int HELLO_PRIVILEGE_FINGERPRINT_BYTES = Long.BYTES;
	/** Legacy HELLO without region fields reports epoch {@code 0} / role {@code 0}. */
	private static final long HELLO_LEGACY_REGION_EPOCH = 0L;
	private static final byte HELLO_LEGACY_REGION_ROLE = 0;
	private static final long HELLO_LEGACY_CATALOG_APPLIED = 0L;
	private static final long HELLO_LEGACY_CATALOG_FINGERPRINT = 0L;
	private static final long HELLO_LEGACY_SEALED_ARTIFACTS_FINGERPRINT = 0L;
	private static final long HELLO_LEGACY_PRIVILEGE_FINGERPRINT = 0L;
	/** Soft cap on HELLO sealed inventory entries (advertising only). */
	private static final int HELLO_SEALED_INVENTORY_MAX_ENTRIES = 16_384;

	private HelloRpcCodec() {
	}

	public static byte[] encodeHello(Hello hello) {
		final byte[] nodeId = hello.nodeId().getBytes(StandardCharsets.UTF_8);
		final byte[] clusterId = hello.clusterId().getBytes(StandardCharsets.UTF_8);
		final byte[] localDc = hello.localDc().getBytes(StandardCharsets.UTF_8);
		final List<SealedShardInventoryEntry> inventory = hello.sealedShardInventory() == null
				? List.of()
				: hello.sealedShardInventory();
		int inventoryBytes = HELLO_SEALED_INVENTORY_COUNT_BYTES;
		for (SealedShardInventoryEntry entry : inventory) {
			if (entry == null || entry.domain() == null) {
				continue;
			}
			final byte[] domainBytes = entry.domain().getBytes(StandardCharsets.UTF_8);
			inventoryBytes += Integer.BYTES + domainBytes.length + Integer.BYTES + Long.BYTES;
		}
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(
				Integer.BYTES + nodeId.length
						+ Integer.BYTES + clusterId.length
						+ Integer.BYTES + localDc.length
						+ Long.BYTES
						+ HELLO_REGION_EPOCH_BYTES
						+ HELLO_REGION_ROLE_BYTES
						+ HELLO_CATALOG_APPLIED_BYTES
						+ HELLO_CATALOG_FINGERPRINT_BYTES
						+ HELLO_SEALED_ARTIFACTS_FINGERPRINT_BYTES
						+ inventoryBytes
						+ HELLO_PRIVILEGE_FINGERPRINT_BYTES);
		EncodeBuffers.putLengthPrefixed(buf, nodeId);
		EncodeBuffers.putLengthPrefixed(buf, clusterId);
		EncodeBuffers.putLengthPrefixed(buf, localDc);
		buf.putLong(hello.schemaEpoch());
		buf.putLong(hello.regionEpoch());
		buf.put(hello.regionRole());
		buf.putLong(hello.catalogAppliedEpoch());
		buf.putLong(hello.catalogFingerprint());
		buf.putLong(hello.sealedArtifactsFingerprint());
		int written = 0;
		final int countPos = buf.position();
		buf.putInt(0);
		for (SealedShardInventoryEntry entry : inventory) {
			if (entry == null || entry.domain() == null) {
				continue;
			}
			if (written >= HELLO_SEALED_INVENTORY_MAX_ENTRIES) {
				break;
			}
			EncodeBuffers.putLengthPrefixed(buf, entry.domain().getBytes(StandardCharsets.UTF_8));
			buf.putInt(entry.shard());
			buf.putLong(entry.identityFingerprint());
			written++;
		}
		buf.putInt(countPos, written);
		buf.putLong(hello.privilegeFingerprint());
		return EncodeBuffers.toByteArray(buf);
	}

	public static Hello decodeHello(byte[] body) {
		final ByteBuffer buf = EncodeBuffers.wrapLe(body);
		final String nodeId = EncodeBuffers.getUtf8(buf);
		final String clusterId = EncodeBuffers.getUtf8(buf);
		final String localDc = EncodeBuffers.getUtf8(buf);
		final long schemaEpoch = buf.getLong();
		long regionEpoch = HELLO_LEGACY_REGION_EPOCH;
		byte regionRole = HELLO_LEGACY_REGION_ROLE;
		long catalogAppliedEpoch = HELLO_LEGACY_CATALOG_APPLIED;
		long catalogFingerprint = HELLO_LEGACY_CATALOG_FINGERPRINT;
		long sealedArtifactsFingerprint = HELLO_LEGACY_SEALED_ARTIFACTS_FINGERPRINT;
		long privilegeFingerprint = HELLO_LEGACY_PRIVILEGE_FINGERPRINT;
		List<SealedShardInventoryEntry> inventory = List.of();
		if (buf.remaining() >= HELLO_REGION_EPOCH_BYTES) {
			regionEpoch = buf.getLong();
		}
		if (buf.remaining() >= HELLO_REGION_ROLE_BYTES) {
			regionRole = buf.get();
		}
		if (buf.remaining() >= HELLO_CATALOG_APPLIED_BYTES) {
			catalogAppliedEpoch = buf.getLong();
		}
		if (buf.remaining() >= HELLO_CATALOG_FINGERPRINT_BYTES) {
			catalogFingerprint = buf.getLong();
		}
		if (buf.remaining() >= HELLO_SEALED_ARTIFACTS_FINGERPRINT_BYTES) {
			sealedArtifactsFingerprint = buf.getLong();
		}
		if (buf.remaining() >= HELLO_SEALED_INVENTORY_COUNT_BYTES) {
			final int count = buf.getInt();
			if (count > 0 && count <= HELLO_SEALED_INVENTORY_MAX_ENTRIES) {
				final List<SealedShardInventoryEntry> entries = new ArrayList<>(count);
				for (int index = 0; index < count; index++) {
					if (buf.remaining() < Integer.BYTES) {
						break;
					}
					final String domain = EncodeBuffers.getUtf8(buf);
					if (buf.remaining() < Integer.BYTES + Long.BYTES) {
						break;
					}
					final int shard = buf.getInt();
					final long fp = buf.getLong();
					entries.add(new SealedShardInventoryEntry(domain, shard, fp));
				}
				inventory = Collections.unmodifiableList(entries);
			}
		}
		if (buf.remaining() >= HELLO_PRIVILEGE_FINGERPRINT_BYTES) {
			privilegeFingerprint = buf.getLong();
		}
		return new Hello(
				nodeId, clusterId, localDc, schemaEpoch, regionEpoch, regionRole,
				catalogAppliedEpoch, catalogFingerprint, sealedArtifactsFingerprint, inventory,
				privilegeFingerprint);
	}
}
