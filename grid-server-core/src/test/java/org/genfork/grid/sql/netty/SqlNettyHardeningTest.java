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

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.IdleStateEvent;
import index.sql.SqlSelectAwait;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlServerRuntime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * SQL Netty hardening: SO_KEEPALIVE parity and safe idle-close.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
class SqlNettyHardeningTest {
	private static final long IDLE_TIMEOUT_MS = 200L;
	/** Bound for blocking read until server idle-close (idle + OS/Netty slack). */
	private static final int PRE_AUTH_READ_TIMEOUT_MS = 2_000;
	private static final int PORTAL_EXTRA_ROWS = 40;
	private static final int AWAIT_PORTAL_TIMEOUT_SEC = 5;
	private static final long AWAIT_SLICE_MS = 5L;
	private static final int TEST_SHARDS = 4;
	private static final int AUTH_REQUEST_ID = 1;
	private static final int SESSION_OPEN_REQUEST_ID = 2;
	private static final int EXEC_REQUEST_ID = 42;
	private static final int EOF = -1;

	@Test
	void sqlServerListenExposesKeepAliveChildOption() throws Exception {
		final int port = freePort();
		try (SqlServerRuntime runtime = SqlServerRuntime.builder()
				.dataDir(Files.createTempDirectory("sql-keepalive"))
				.shards(TEST_SHARDS)
				.durability(false)
				.listen(true)
				.bind("127.0.0.1", port)
				.auth("u", "p")
				.build()
				.start()) {
			final SqlServer server = runtime.tcpServer();
			assertEquals(Boolean.TRUE, server.childTcpNoDelay());
			assertEquals(Boolean.TRUE, server.childSoKeepAlive());
			assertEquals(SqlServer.DEFAULT_IDLE_TIMEOUT_MS, server.idleTimeoutMs());
		}
	}

	@Test
	void sqlServerPropsIdleDefaultIsOff() {
		assertEquals(
				SqlServer.DEFAULT_IDLE_TIMEOUT_MS,
				new GridConfigurationProperties().getSqlServer().getIdleTimeoutMs());
	}

	@Test
	void readerIdleClosesBeforeAuth() {
		final SqlExecHandler handler = new SqlExecHandler(
				new SqlEngine(new TableCatalog(tempCatalog()), null, TEST_SHARDS),
				"",
				"",
				SqlServer.DEFAULT_MAX_TX_CONTEXTS);
		final EmbeddedChannel channel = new EmbeddedChannel(new SqlIdleCloseHandler(), handler);
		assertTrue(handler.mayIdleClose());
		channel.pipeline().fireUserEventTriggered(IdleStateEvent.READER_IDLE_STATE_EVENT);
		assertFalse(channel.isActive());
	}

	@Test
	void readerIdleKeepsChannelOpenWhilePortalActive() throws Exception {
		final SqlEngine engine = new SqlEngine(new TableCatalog(tempCatalog()), null, TEST_SHARDS);
		final SqlExecHandler handler = new SqlExecHandler(
				engine, "", "", SqlServer.DEFAULT_MAX_TX_CONTEXTS);
		final EmbeddedChannel channel = new EmbeddedChannel(new SqlIdleCloseHandler(), handler);

		channel.writeInbound(new SqlFrame(SqlOpcode.AUTH, AUTH_REQUEST_ID, SqlWire.auth("", "")));
		assertEquals(SqlOpcode.AUTH_OK, ((SqlFrame) channel.readOutbound()).opcode());
		assertTrue(handler.mayIdleClose());

		final int n = SqlWire.DEFAULT_FETCH_WINDOW + PORTAL_EXTRA_ROWS;
		engine.execute("CREATE TABLE t (id INT PRIMARY KEY, v INT)");
		for (int i = 1; i <= n; i++) {
			engine.execute("INSERT INTO t (id, v) VALUES (" + i + ", " + i + ")");
		}
		SqlSelectAwait.awaitRowCount(engine, "SELECT id FROM t", n);

		channel.writeInbound(new SqlFrame(
				SqlOpcode.SESSION_OPEN, SESSION_OPEN_REQUEST_ID, SqlWire.sessionOpen("public")));
		final int sessionId = SqlWire.readSessionId(((SqlFrame) channel.readOutbound()).payload());
		channel.writeInbound(new SqlFrame(
				SqlOpcode.EXEC,
				EXEC_REQUEST_ID,
				SqlWire.exec(sessionId, "SELECT id, v FROM t", new Object[0])
		));
		assertTrue(awaitPortal(channel, handler, 1), "portal must open");
		assertFalse(handler.mayIdleClose());

		channel.pipeline().fireUserEventTriggered(IdleStateEvent.READER_IDLE_STATE_EVENT);
		assertTrue(channel.isActive(), "idle must not close while portal open");

		channel.writeInbound(new SqlFrame(SqlOpcode.CANCEL, EXEC_REQUEST_ID, SqlWire.cancel()));
		assertTrue(awaitNoPortal(channel, handler), "portal must clear after CANCEL");
		assertTrue(handler.mayIdleClose());

		channel.pipeline().fireUserEventTriggered(IdleStateEvent.READER_IDLE_STATE_EVENT);
		assertFalse(channel.isActive(), "idle closes after portal gone");
	}

	@Test
	void preAuthTcpIdleClosesSocket() throws Exception {
		final int port = freePort();
		try (SqlServerRuntime runtime = SqlServerRuntime.builder()
				.dataDir(Files.createTempDirectory("sql-idle-preauth"))
				.shards(TEST_SHARDS)
				.durability(false)
				.listen(true)
				.bind("127.0.0.1", port)
				.auth("u", "p")
				.idleTimeoutMs(IDLE_TIMEOUT_MS)
				.build()
				.start()) {
			assertEquals(IDLE_TIMEOUT_MS, runtime.tcpServer().idleTimeoutMs());
			try (Socket socket = new Socket("127.0.0.1", port)) {
				socket.setSoTimeout(PRE_AUTH_READ_TIMEOUT_MS);
				final InputStream in = socket.getInputStream();
				final int first = in.read();
				assertEquals(EOF, first, "idle pre-AUTH peer must close (EOF), got byte=" + first);
			} catch (SocketTimeoutException ex) {
				fail("server did not idle-close pre-AUTH client within " + PRE_AUTH_READ_TIMEOUT_MS + "ms");
			}
		}
	}

	private static boolean awaitPortal(
			EmbeddedChannel channel,
			SqlExecHandler handler,
			int expected
	) throws InterruptedException {
		final long deadline = System.nanoTime()
				+ TimeUnit.SECONDS.toNanos(AWAIT_PORTAL_TIMEOUT_SEC);
		while (System.nanoTime() < deadline) {
			channel.runPendingTasks();
			if (handler.portalCount() >= expected) {
				return true;
			}
			TimeUnit.MILLISECONDS.sleep(AWAIT_SLICE_MS);
		}
		return handler.portalCount() >= expected;
	}

	private static boolean awaitNoPortal(
			EmbeddedChannel channel,
			SqlExecHandler handler
	) throws InterruptedException {
		final long deadline = System.nanoTime()
				+ TimeUnit.SECONDS.toNanos(AWAIT_PORTAL_TIMEOUT_SEC);
		while (System.nanoTime() < deadline) {
			channel.runPendingTasks();
			if (handler.portalCount() == 0 && handler.inFlightExecCount() == 0) {
				return true;
			}
			TimeUnit.MILLISECONDS.sleep(AWAIT_SLICE_MS);
		}
		return handler.portalCount() == 0 && handler.inFlightExecCount() == 0;
	}

	private static Path tempCatalog() {
		try {
			return Files.createTempDirectory("sql-idle-embed");
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static int freePort() throws Exception {
		try (ServerSocket ss = new ServerSocket(0)) {
			ss.setReuseAddress(true);
			return ss.getLocalPort();
		}
	}
}
