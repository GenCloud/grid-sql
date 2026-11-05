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

import org.genfork.grid.replication.netty.NettyReplicationTransport;
import org.genfork.grid.replication.transport.ReplicationPeer;
import org.genfork.grid.threading.SerialTaskQueue;
import org.genfork.grid.threading.ThreadService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HELLO catch-up mailbox runs off the caller thread and is distinct from orchid serial drain.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
class HelloCatchUpMailboxIT {

	private static final long AWAIT_MS = 5_000L;

	@Test
	void submitHelloCatchUpRunsOffCallerThread() throws Exception {
		final NettyReplicationTransport transport = new NettyReplicationTransport(
				"n1", "c1", "dc-a", 0L, "127.0.0.1", 0, 1_000L, 1 << 20,
				List.<ReplicationPeer>of());
		final Thread caller = Thread.currentThread();
		final AtomicReference<Thread> worker = new AtomicReference<>();
		final CountDownLatch done = new CountDownLatch(1);
		transport.submitHelloCatchUp(() -> {
			worker.set(Thread.currentThread());
			done.countDown();
		});
		assertTrue(done.await(AWAIT_MS, TimeUnit.MILLISECONDS), "helloCatchUp must run");
		assertNotEquals(caller, worker.get(), "must not run on Netty-EL caller thread");
	}

	@Test
	void helloCatchUpDoesNotShareOrchidSerialQueue() throws Exception {
		final SerialTaskQueue orchidLike = new SerialTaskQueue();
		final CountDownLatch orchidStarted = new CountDownLatch(1);
		final CountDownLatch releaseOrchid = new CountDownLatch(1);
		final CountDownLatch helloDone = new CountDownLatch(1);
		orchidLike.submit(ThreadService.getLogicExecutor(), () -> {
			orchidStarted.countDown();
			try {
				releaseOrchid.await(AWAIT_MS, TimeUnit.MILLISECONDS);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			}
		});
		assertTrue(orchidStarted.await(AWAIT_MS, TimeUnit.MILLISECONDS));

		final NettyReplicationTransport transport = new NettyReplicationTransport(
				"n1", "c1", "dc-a", 0L, "127.0.0.1", 0, 1_000L, 1 << 20,
				List.<ReplicationPeer>of());
		transport.submitHelloCatchUp(helloDone::countDown);
		assertTrue(helloDone.await(AWAIT_MS, TimeUnit.MILLISECONDS),
				"helloCatchUp must progress while orchid-like serial task is blocked");
		releaseOrchid.countDown();
	}
}
