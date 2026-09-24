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
package org.genfork.grid.sql.netty;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.context.config.GridConfigurationProperties.ReplicationPeerProps;
import org.genfork.grid.context.config.GridConfigurationProperties.ReplicationProps;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.region.RegionRole;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.client.ServerMeta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Region epoch fencing surfaces on AUTH_OK / PROMOTE_NOTIFY ServerMeta.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SqlRegionEpochPromoteIT {
	private static final String NODE_ID = "region-a1";
	private static final String PEER_ID = "region-b1";
	private static final String CLUSTER_ID = "region-cluster";
	private static final String LOCAL_DC = "dc-a";
	private static final String BIND_HOST = "127.0.0.1";
	private static final int AUTH_REQUEST_ID = 1;
	private static final int UNREACHABLE_PEER_PORT = 1;
	private static final int TEST_SHARDS = 1;
	private static final int MAX_TX_CONTEXTS = 1;
	private static final long AWAIT_TIMEOUT_MS = 5_000L;
	private static final long AWAIT_STEP_MS = 10L;
	private static final long BOOT_EPOCH = 1L;

	@TempDir
	Path tempDir;

	@Test
	void authOkCarriesRegionEpochAndFenceClearsWriter() throws Exception {
		final ReplicationCoordinator coordinator = new ReplicationCoordinator(properties());
		final SqlEngine engine = new SqlEngine(
				new TableCatalog(tempDir.resolve("catalog")),
				coordinator,
				TEST_SHARDS
		);
		final SqlChannelRegistry registry = new SqlChannelRegistry();
		final SqlExecHandler handler = new SqlExecHandler(
				engine, "", "", MAX_TX_CONTEXTS, coordinator, registry);
		final EmbeddedChannel channel = new EmbeddedChannel(handler);
		try {
			coordinator.start();
			channel.writeInbound(new SqlFrame(SqlOpcode.AUTH, AUTH_REQUEST_ID, SqlWire.auth("", "")));
			final SqlFrame authOk = channel.readOutbound();
			assertNotNull(authOk);
			assertEquals(SqlOpcode.AUTH_OK, authOk.opcode());
			final ServerMeta meta = SqlWire.readServerMeta(authOk.payload());
			assertEquals(BOOT_EPOCH, meta.regionEpoch());
			assertEquals(RegionRole.ACTIVE.wireCode(), meta.regionRole());

			assertTrue(coordinator.observePeerRegionEpoch(BOOT_EPOCH + 1L, PEER_ID));
			assertFalse(coordinator.regionAllowsWrites());
			assertEquals(RegionRole.HOLD, coordinator.getRegionCoordinator().regionRole());
		} finally {
			channel.finishAndReleaseAll();
			coordinator.stop();
		}
	}

	@Test
	void claimAdvancesEpochAndWriterEligibleAfterQuorum() throws Exception {
		final ReplicationCoordinator coordinator = new ReplicationCoordinator(holdProperties());
		try {
			coordinator.start();
			assertFalse(coordinator.regionAllowsWrites());
			assertTrue(coordinator.tryRegionClaim(2));
			assertTrue(coordinator.regionAllowsWrites());
			assertEquals(BOOT_EPOCH + 1L, coordinator.regionEpoch());
			assertEquals(RegionRole.ACTIVE.wireCode(), coordinator.regionRoleWire());
		} finally {
			coordinator.stop();
		}
	}

	@Test
	void backgroundClaimTickPromotesHoldWhenRemoteDcSilent() throws Exception {
		final ReplicationCoordinator coordinator = new ReplicationCoordinator(soloHoldProperties());
		try {
			coordinator.start();
			assertFalse(coordinator.regionAllowsWrites());
			assertTrue(awaitClaim(coordinator));
			assertTrue(coordinator.regionAllowsWrites());
			assertEquals(BOOT_EPOCH + 1L, coordinator.regionEpoch());
			assertEquals(RegionRole.ACTIVE.wireCode(), coordinator.regionRoleWire());
		} finally {
			coordinator.stop();
		}
	}

	private static boolean awaitClaim(ReplicationCoordinator coordinator) {
		final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AWAIT_TIMEOUT_MS);
		while (System.nanoTime() < deadline) {
			if (coordinator.regionRoleWire() == RegionRole.ACTIVE.wireCode()
					&& coordinator.regionEpoch() > BOOT_EPOCH) {
				return true;
			}
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(AWAIT_STEP_MS));
		}
		return false;
	}

	private GridConfigurationProperties properties() throws Exception {
		return baseProperties(RegionRole.ACTIVE, true);
	}

	private GridConfigurationProperties holdProperties() throws Exception {
		return baseProperties(RegionRole.HOLD, false);
	}

	/** Solo Hold: majority-of-1 so self-ACK completes claim when remote DC is silent. */
	private GridConfigurationProperties soloHoldProperties() throws Exception {
		final GridConfigurationProperties properties = baseProperties(RegionRole.HOLD, false);
		properties.getReplication().getRegion().setQuorumSize(1);
		return properties;
	}

	private GridConfigurationProperties baseProperties(RegionRole role, boolean writeAdmission)
			throws Exception {
		final GridConfigurationProperties properties = new GridConfigurationProperties();
		final ReplicationProps replication = properties.getReplication();
		replication.setEnabled(true);
		replication.setNodeId(NODE_ID);
		replication.setClusterId(CLUSTER_ID);
		replication.getOrchid().setOrderThreshold(0.0);
		replication.getOrchid().setTickMs(AWAIT_STEP_MS);
		replication.getTransport().setBindHost(BIND_HOST);
		replication.getTransport().setBindPort(freePort());
		replication.getTransport().setPeers(List.of(peer()));
		replication.getCrossDc().setEnabled(true);
		replication.getCrossDc().setLocalDc(LOCAL_DC);
		replication.getCrossDc().setWriteAdmission(writeAdmission);
		replication.getOpLog().setDataDir(tempDir.resolve("repl").toString());
		replication.getOpLog().setFsync(false);
		replication.getRegion().setEnabled(true);
		replication.getRegion().setRole(role);
		replication.getRegion().setEpoch(BOOT_EPOCH);
		replication.getRegion().setQuorumSize(2);
		replication.getRegion().setClaimTimeoutMs(1L);
		return properties;
	}

	private ReplicationPeerProps peer() {
		final ReplicationPeerProps peer = new ReplicationPeerProps();
		peer.setId(PEER_ID);
		peer.setHost(BIND_HOST);
		peer.setPort(UNREACHABLE_PEER_PORT);
		peer.setDc("dc-b");
		return peer;
	}

	private static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}
}