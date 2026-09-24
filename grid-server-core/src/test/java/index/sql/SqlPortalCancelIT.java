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
package index.sql;

import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.metrics.DistributedQueryMetrics;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.netty.SqlExecHandler;
import org.genfork.grid.sql.netty.SqlFrame;
import org.genfork.grid.sql.netty.SqlOpcode;
import org.genfork.grid.sql.netty.SqlWire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Portal CANCEL UX: idempotent after stream open / drain; no server portal state leak.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SqlPortalCancelIT {
	private SqlEngine engine;
	private SqlExecHandler handler;
	private EmbeddedChannel channel;

	@BeforeEach
	void setUp() throws Exception {
		engine = new SqlEngine(
				new TableCatalog(Files.createTempDirectory("portal-cancel")),
				null,
				4
		);
		handler = new SqlExecHandler(engine, "", "", 8);
		channel = new EmbeddedChannel(handler);
	}

	@AfterEach
	void tearDown() {
		if (channel != null) {
			channel.finishAndReleaseAll();
		}
	}

	@Test
	void streamThenCancelTwiceIsIdempotentAndLeakFree() throws Exception {
		final long cancelRequestsBefore = DistributedQueryMetrics.cancelRequests();
		final long cancelActiveBefore = DistributedQueryMetrics.cancelActive();
		final int n = SqlWire.DEFAULT_FETCH_WINDOW + 40;
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v INT)");
		for (int i = 1; i <= n; i++) {
			engine.execute("INSERT INTO t (id, v) VALUES (" + i + ", " + i + ")");
		}
		SqlSelectAwait.awaitRowCount(engine, "SELECT id FROM t", n);

		channel.writeInbound(new SqlFrame(SqlOpcode.SESSION_OPEN, 1, SqlWire.sessionOpen("public")));
		final SqlFrame openOk = channel.readOutbound();
		assertEquals(SqlOpcode.SESSION_OPEN_OK, openOk.opcode());
		final int sessionId = SqlWire.readSessionId(openOk.payload());

		final int execReq = 42;
		channel.writeInbound(new SqlFrame(
				SqlOpcode.EXEC,
				execReq,
				SqlWire.exec(sessionId, "SELECT id, v FROM t", new Object[0])
		));

		assertTrue(awaitPortal(1), "portal must open for oversized RESULT_SET");
		assertEquals(1, handler.portalCount());

		channel.writeInbound(new SqlFrame(SqlOpcode.CANCEL, execReq, SqlWire.cancel()));
		channel.writeInbound(new SqlFrame(SqlOpcode.CANCEL, execReq, SqlWire.cancel()));
		channel.runPendingTasks();

		assertEquals(0, handler.portalCount(), "portal must be gone");
		assertEquals(0, handler.pendingCancelCount(), "no cancel-intent leak");
		assertEquals(0, handler.inFlightExecCount(), "no in-flight leak");
		assertEquals(cancelRequestsBefore + 2L, DistributedQueryMetrics.cancelRequests());
		assertEquals(cancelActiveBefore + 1L, DistributedQueryMetrics.cancelActive());

		int cancelAcks = 0;
		for (Object out; (out = channel.readOutbound()) != null; ) {
			if (out instanceof SqlFrame f && f.opcode() == SqlOpcode.EXEC_DONE && f.requestId() == execReq) {
				cancelAcks++;
			}
		}
		assertTrue(cancelAcks >= 2, "double CANCEL must ACK twice, got " + cancelAcks);

		channel.writeInbound(new SqlFrame(
				SqlOpcode.EXEC,
				99,
				SqlWire.exec(sessionId, "SELECT id FROM t WHERE id = 1", new Object[0])
		));
		assertTrue(awaitOutboundDone(99), "session usable after double CANCEL");
		assertEquals(0, handler.portalCount());
		assertEquals(0, handler.pendingCancelCount());
		assertEquals(0, handler.inFlightExecCount());
	}

	@Test
	void cancelAfterFetchDrainIsIdempotent() throws Exception {
		final int n = SqlWire.DEFAULT_FETCH_WINDOW + 10;
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v INT)");
		for (int i = 1; i <= n; i++) {
			engine.execute("INSERT INTO t (id, v) VALUES (" + i + ", " + i + ")");
		}
		SqlSelectAwait.awaitRowCount(engine, "SELECT id FROM t", n);

		channel.writeInbound(new SqlFrame(SqlOpcode.SESSION_OPEN, 1, SqlWire.sessionOpen("public")));
		final int sessionId = SqlWire.readSessionId(((SqlFrame) channel.readOutbound()).payload());

		final int execReq = 7;
		channel.writeInbound(new SqlFrame(
				SqlOpcode.EXEC,
				execReq,
				SqlWire.exec(sessionId, "SELECT id FROM t", new Object[0])
		));
		assertTrue(awaitPortal(1));

		channel.writeInbound(new SqlFrame(SqlOpcode.FETCH, execReq, SqlWire.fetch(n)));
		assertTrue(awaitNoPortal(), "FETCH must drain portal");

		channel.writeInbound(new SqlFrame(SqlOpcode.CANCEL, execReq, SqlWire.cancel()));
		channel.writeInbound(new SqlFrame(SqlOpcode.CANCEL, execReq, SqlWire.cancel()));
		channel.runPendingTasks();

		assertEquals(0, handler.portalCount());
		assertEquals(0, handler.pendingCancelCount());
		assertEquals(0, handler.inFlightExecCount());
	}

	private boolean awaitPortal(int expected) throws InterruptedException {
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			channel.runPendingTasks();
			if (handler.portalCount() >= expected) {
				return true;
			}
			TimeUnit.MILLISECONDS.sleep(5);
		}
		return handler.portalCount() >= expected;
	}

	private boolean awaitNoPortal() throws InterruptedException {
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			channel.runPendingTasks();
			if (handler.portalCount() == 0 && handler.inFlightExecCount() == 0) {
				return true;
			}
			TimeUnit.MILLISECONDS.sleep(5);
		}
		return handler.portalCount() == 0;
	}

	private boolean awaitOutboundDone(int requestId) throws InterruptedException {
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			channel.runPendingTasks();
			for (Object out; (out = channel.readOutbound()) != null; ) {
				if (out instanceof SqlFrame f
						&& f.requestId() == requestId
						&& (f.opcode() == SqlOpcode.EXEC_DONE || f.opcode() == SqlOpcode.ERROR)) {
					return f.opcode() == SqlOpcode.EXEC_DONE;
				}
			}
			TimeUnit.MILLISECONDS.sleep(5);
		}
		return false;
	}
}
