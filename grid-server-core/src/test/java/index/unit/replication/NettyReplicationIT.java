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
import org.genfork.grid.replication.codec.OpLogCodec;
import org.genfork.grid.replication.codec.OpLogSegment;
import org.genfork.grid.replication.codec.ReplicationOp;
import org.genfork.grid.replication.codec.ReplicationOpType;
import org.genfork.grid.replication.metrics.ReplicationMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi-instance ship over Netty TCP (ORCHID + OPLOG_PUSH).
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public class NettyReplicationIT {

	@TempDir
	Path tempDir;

	@Test
	void oplogPushOverTcp() throws Exception {
		final int portA = freePort();
		final int portB = freePort();

		final GridConfigurationProperties propsA = ReplTestSupport.props(
				"n-a", "netty-it", "dc-a", portA, tempDir.resolve("a"),
				List.of(ReplTestSupport.peer("n-b", "dc-a", portB))
		);
		final GridConfigurationProperties propsB = ReplTestSupport.props(
				"n-b", "netty-it", "dc-a", portB, tempDir.resolve("b"),
				List.of(ReplTestSupport.peer("n-a", "dc-a", portA))
		);

		final ReplicationCoordinator a = new ReplicationCoordinator(propsA);
		final ReplicationCoordinator b = new ReplicationCoordinator(propsB);

		final GridEntriesProcessor procA = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		final GridEntriesProcessor procB = new GridEntriesProcessor(0, new GridScalableMap(), null, null);
		a.registerDomain("demo.Netty", ReplTestSupport.singleShard(procA), true);
		b.registerDomain("demo.Netty", ReplTestSupport.singleShard(procB), true);

		a.start();
		b.start();

		final long deadline = System.currentTimeMillis() + 5000;
		while (System.currentTimeMillis() < deadline) {
			final ReplicationOp op = OpLogCodec.withChecksum(new ReplicationOp(
					"demo.Netty", 0, 1L, ReplicationOpType.UPSERT,
					new byte[]{1, 2}, new byte[]{9, 9, 9}, 1L, 0L
			));
			final OpLogSegment segment = new OpLogSegment(
					"demo.Netty", 0, 1L, 1L, List.of(op), OpLogCodec.segmentChecksum(List.of(op))
			);
			a.getNettyTransport().pushSegment("n-b", segment);
			Thread.sleep(100);
			if (procB.get(new byte[]{1, 2}) != null) {
				break;
			}
		}

		final byte[] got = procB.get(new byte[]{1, 2});
		assertArrayEquals(new byte[]{9, 9, 9}, got);
		assertTrue(ReplicationMetrics.oplogPushSent() > 0);
		assertTrue(ReplicationMetrics.oplogPushRecv() > 0);

		a.stop();
		b.stop();
	}

	private static int freePort() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}
}
