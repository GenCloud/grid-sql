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
package org.genfork.grid.replication.transport;

import org.genfork.grid.replication.util.SealedShardInventoryEntry;

import java.util.List;

/**
 * Peer discovered via HELLO (same clusterId); used for dynamic membership and region epoch observe.
 * <p>
 * {@code catalogAppliedEpoch} is the peer {@code _catalog} OpLog applied watermark (catch-up fence;
 * never mixed with duplex HELLO {@code schemaEpoch}).
 * {@code catalogFingerprint} is the peer live object-set hash (replica-read / reconcile fence).
 * {@code sealedArtifactsFingerprint} is the peer sealed aggregate name+size identity (metrics).
 * {@code sealedShardInventory} is optional per-shard identity for differential heal / fence.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public record DiscoveredPeer(
		String id,
		String host,
		int port,
		String dc,
		long regionEpoch,
		byte regionRole,
		long catalogAppliedEpoch,
		long catalogFingerprint,
		long sealedArtifactsFingerprint,
		List<SealedShardInventoryEntry> sealedShardInventory
) {
}
