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
import org.genfork.grid.context.config.GridConfigurationProperties.ReplicationPeerProps;
import org.genfork.grid.context.config.GridConfigurationProperties.ReplicationProps;
import org.genfork.grid.mem.stage.GridEntriesProcessor;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

/**
 * Shared test props: always-on-disk OpLog under TempDir, fsync off.
 * Convenience props use orderThreshold=0 for fast IT sync; safety suite uses {@link #safetyProps}.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class ReplTestSupport {
	private ReplTestSupport() {
	}

	public static GridConfigurationProperties props(String nodeId, String clusterId, String dc, int bindPort,
	                                         Path dataDir, List<ReplicationPeerProps> peers) {
		final GridConfigurationProperties properties = new GridConfigurationProperties();
		final ReplicationProps replication = properties.getReplication();
		replication.setEnabled(true);
		replication.setNodeId(nodeId);
		replication.setClusterId(clusterId);
		replication.getOrchid().setOrderThreshold(0.0);
		replication.getOrchid().setTickMs(10);
		replication.getOrchid().setDigestQuorum("MAJORITY");
		replication.getTransport().setBindHost("127.0.0.1");
		replication.getTransport().setBindPort(bindPort);
		replication.getTransport().setPeers(peers);
		replication.getCrossDc().setLocalDc(dc);
		replication.getCrossDc().setEnabled(true);
		replication.getCrossDc().setMode("ASYNC_SHIP");
		replication.getCrossDc().setBatchMaxOps(1);
		replication.getCrossDc().setBatchMaxWaitMs(5);
		replication.getOpLog().setDataDir(dataDir.toString());
		replication.getOpLog().setFsync(false);
		replication.getOpLog().setSegmentSize(1);
		replication.getRepair().setHomologousEnabled(true);
		replication.getRepair().setReconcileIntervalMs(60_000);
		replication.getSwarm().setEnabled(true);
		return properties;
	}

	/**
	 * Safety-suite props: real order threshold + Kuramoto defaults (no orderThreshold=0.0).
	 */
	public static GridConfigurationProperties safetyProps(String nodeId, String clusterId, String dc, int bindPort,
	                                                      Path dataDir, List<ReplicationPeerProps> peers) {
		final GridConfigurationProperties properties = props(nodeId, clusterId, dc, bindPort, dataDir, peers);
		properties.getReplication().getOrchid().setOrderThreshold(0.5);
		properties.getReplication().getOrchid().setNaturalFreqHz(1.0);
		properties.getReplication().getOrchid().setCoupling(15.0);
		return properties;
	}

	/** Single-shard domain binding for ITs (null past shard 0 — required by sealed bind probe). */
	public static Function<Integer, GridEntriesProcessor> singleShard(GridEntriesProcessor processor) {
		return shard -> shard == 0 ? processor : null;
	}

	public static ReplicationPeerProps peer(String id, String dc, int port) {
		final ReplicationPeerProps p = new ReplicationPeerProps();
		p.setId(id);
		p.setHost("127.0.0.1");
		p.setPort(port);
		p.setDc(dc);
		return p;
	}
}