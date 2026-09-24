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

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.google.common.annotations.VisibleForTesting;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import org.genfork.grid.metrics.DistributedQueryMetrics;
import org.genfork.grid.metrics.SqlTxMetrics;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.sql.client.ServerMeta;
import org.genfork.grid.threading.SerialTaskQueue;
import org.genfork.grid.threading.ThreadService;

/**
 * Server-side AUTH / SESSION_* / EXEC / BATCH_EXEC / FETCH / CANCEL → {@link SqlEngine}.
 * One TCP channel multiplexes many {@link SqlSession} by sessionId.
 * RESULT_SET uses requestId portals: initial window then client FETCH; CANCEL drops portal.
 * Per-session EXECs are serialized via {@link SerialTaskQueue} (no monitor pinning).
 * <p>
 * Portal lifecycle is centralized in {@link #dropPortal(int)} / {@link #handleCancel}:
 * CANCEL is idempotent after FETCH drain or a prior CANCEL (no leaked cancel-intent entries).
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlExecHandler extends SimpleChannelInboundHandler<SqlFrame> {
	public static final int DEFAULT_MAX_TX_CONTEXTS = 256;
	private static final int SERVER_PUSH_REQUEST_ID = 0;
	private static final String STALE_OVERRIDE_NODE_ID = "stale-override";

	private final SqlEngine engine;
	private final String expectedUser;
	private final String expectedPassword;
	private final int maxTxContexts;
	private final ReplicationCoordinator replication;
	private final SqlChannelRegistry channelRegistry;
	/** Shared with {@link SqlServer}: non-null Boolean forces applyLagStale in AUTH meta. */
	private final AtomicReference<Boolean> applyLagStaleOverride;
	private final AtomicBoolean authed = new AtomicBoolean(false);
	private final AtomicInteger sessionSeq = new AtomicInteger(1);
	private final Map<Integer, SqlSession> sessions = new ConcurrentHashMap<>();
	private final Map<Integer, SerialTaskQueue> sessionQueues = new ConcurrentHashMap<>();
	/** Open RESULT_SET portals keyed by EXEC / BATCH_EXEC requestId. */
	private final Map<Integer, Portal> portals = new ConcurrentHashMap<>();
	/** CANCEL before portal created (in-flight EXEC / BATCH only). */
	private final Map<Integer, Boolean> cancelledBeforePortal = new ConcurrentHashMap<>();
	/**
	 * EXEC / BATCH_EXEC still active (running, waiting on FETCH portal, or between batch steps).
	 * Cleared only when the request is terminal (done / aborted).
	 */
	private final Set<Integer> inFlightExec = ConcurrentHashMap.newKeySet();
	/** Logic VT registry for cooperative CANCEL interrupt during lock park. */
	private final SqlExecInterruptRegistry interruptRegistry = new SqlExecInterruptRegistry();
	private volatile String authUser = "anonymous";

	public SqlExecHandler(SqlEngine engine, String expectedUser, String expectedPassword) {
		this(engine, expectedUser, expectedPassword, DEFAULT_MAX_TX_CONTEXTS, null);
	}

	public SqlExecHandler(SqlEngine engine, String expectedUser, String expectedPassword, int maxTxContexts) {
		this(engine, expectedUser, expectedPassword, maxTxContexts, null);
	}

	public SqlExecHandler(
			SqlEngine engine,
			String expectedUser,
			String expectedPassword,
			int maxTxContexts,
			ReplicationCoordinator replication
	) {
		this(engine, expectedUser, expectedPassword, maxTxContexts, replication, null);
	}

	SqlExecHandler(
			SqlEngine engine,
			String expectedUser,
			String expectedPassword,
			int maxTxContexts,
			ReplicationCoordinator replication,
			SqlChannelRegistry channelRegistry
	) {
		this(engine, expectedUser, expectedPassword, maxTxContexts, replication, channelRegistry, null);
	}

	SqlExecHandler(
			SqlEngine engine,
			String expectedUser,
			String expectedPassword,
			int maxTxContexts,
			ReplicationCoordinator replication,
			SqlChannelRegistry channelRegistry,
			AtomicReference<Boolean> applyLagStaleOverride
	) {
		this.engine = engine;
		this.expectedUser = expectedUser == null ? "" : expectedUser;
		this.expectedPassword = expectedPassword == null ? "" : expectedPassword;
		this.maxTxContexts = maxTxContexts <= 0 ? Integer.MAX_VALUE : maxTxContexts;
		this.replication = replication;
		this.channelRegistry = channelRegistry;
		this.applyLagStaleOverride = applyLagStaleOverride;
	}

	/** Open logical sessions on this TCP channel (cheap gauge). */
	public int openSessionCount() {
		return sessions.size();
	}

	/** Active RESULT_SET portals (for tests). */
	public int portalCount() {
		return portals.size();
	}

	/** Cancel-intent entries waiting on in-flight EXEC (for tests). */
	public int pendingCancelCount() {
		return cancelledBeforePortal.size();
	}

	/** In-flight EXEC / BATCH_EXEC count (for tests). */
	public int inFlightExecCount() {
		return inFlightExec.size();
	}

	@VisibleForTesting
	SqlExecInterruptRegistry interruptRegistry() {
		return interruptRegistry;
	}

	@Override
	public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
		if (channelRegistry != null) {
			channelRegistry.register(this, ctx);
		}
		super.handlerAdded(ctx);
	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, SqlFrame frame) {
		try {
			switch (frame.opcode()) {
				case SqlOpcode.AUTH -> handleAuth(ctx, frame);
				case SqlOpcode.SESSION_OPEN -> handleSessionOpen(ctx, frame);
				case SqlOpcode.SESSION_CLOSE -> handleSessionClose(ctx, frame);
				case SqlOpcode.EXEC -> handleExec(ctx, frame);
				case SqlOpcode.BATCH_EXEC -> handleBatchExec(ctx, frame);
				case SqlOpcode.FETCH -> handleFetch(ctx, frame);
				case SqlOpcode.CANCEL -> handleCancel(ctx, frame);
				default -> ctx.writeAndFlush(new SqlFrame(SqlOpcode.ERROR, frame.requestId(),
						SqlWire.error(SqlWireErrorCodes.UNKNOWN_OPCODE, "unknown opcode " + frame.opcode())));
			}
		} catch (Exception ex) {
			ctx.writeAndFlush(new SqlFrame(SqlOpcode.ERROR, frame.requestId(),
					SqlWire.error(SqlWireErrorCodes.EXEC_FAILED,
							ex.getMessage() == null ? ex.toString() : ex.getMessage())));
		}
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		if (channelRegistry != null) {
			channelRegistry.unregister(this);
		}
		portals.clear();
		cancelledBeforePortal.clear();
		inFlightExec.clear();
		interruptRegistry.clear();
		super.channelInactive(ctx);
	}

	@Override
	public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
		if (channelRegistry != null) {
			channelRegistry.unregister(this);
		}
		super.handlerRemoved(ctx);
	}

	private void handleAuth(ChannelHandlerContext ctx, SqlFrame frame) {
		final String[] creds = SqlWire.readAuth(frame.payload());
		final String gotUser = creds[0] == null ? "" : creds[0];
		final String gotPass = creds[1] == null ? "" : creds[1];
		final boolean ok;
		if (engine.privileges().isOpen()) {
			final boolean openAuth = expectedUser.isEmpty() && expectedPassword.isEmpty();
			ok = openAuth || (expectedUser.equals(gotUser) && expectedPassword.equals(gotPass));
		} else {
			ok = engine.privileges().authenticate(gotUser, gotPass);
		}
		if (!ok) {
			ctx.writeAndFlush(new SqlFrame(SqlOpcode.ERROR, frame.requestId(),
					SqlWire.error(SqlWireErrorCodes.AUTH_FAILED, "AUTH failed")));
			return;
		}
		authUser = gotUser.isEmpty() ? "anonymous" : gotUser;
		authed.set(true);
		ctx.writeAndFlush(new SqlFrame(SqlOpcode.AUTH_OK, frame.requestId(), SqlWire.serverMeta(serverMeta())));
	}

	private void handleSessionOpen(ChannelHandlerContext ctx, SqlFrame frame) {
		if (!requireAuth(ctx, frame)) {
			return;
		}
		if (sessions.size() >= maxTxContexts) {
			ctx.writeAndFlush(new SqlFrame(SqlOpcode.ERROR, frame.requestId(),
					SqlWire.error(SqlWireErrorCodes.MAX_TX_CONTEXTS,
							"maxTxContexts=" + maxTxContexts + " exhausted")));
			return;
		}
		final SessionRoleWire.SessionOpen open = SqlWire.readSessionOpen(frame.payload());
		final int id = sessionSeq.getAndIncrement();
		final SqlSession session = engine.newSession(authUser, true);
		session.setCurrentSchema(open.schema());
		session.setSessionRole(open.role());
		if (open.timezoneOrNull() != null) {
			try {
				session.setTimezone(ZoneId.of(open.timezoneOrNull()));
			} catch (RuntimeException ignored) {
				/* keep engine default */
			}
		}
		sessions.put(id, session);
		sessionQueues.put(id, new SerialTaskQueue());
		SqlTxMetrics.recordSessionOpen();
		ctx.writeAndFlush(new SqlFrame(SqlOpcode.SESSION_OPEN_OK, frame.requestId(), SqlWire.sessionId(id)));
	}

	private void handleSessionClose(ChannelHandlerContext ctx, SqlFrame frame) {
		if (!requireAuth(ctx, frame)) {
			return;
		}
		final int id = SqlWire.readSessionId(frame.payload());
		final SqlSession removed = sessions.remove(id);
		sessionQueues.remove(id);
		if (removed != null && removed.inTransaction()) {
			removed.endTx();
		}
		ctx.writeAndFlush(new SqlFrame(SqlOpcode.SESSION_CLOSE_OK, frame.requestId(), new byte[0]));
	}

	private void handleExec(ChannelHandlerContext ctx, SqlFrame frame) {
		if (!requireAuth(ctx, frame)) {
			return;
		}
		final SqlWire.ExecPayload exec = SqlWire.readExec(frame.payload());
		final SqlSession session = sessions.get(exec.sessionId());
		if (session == null) {
			ctx.writeAndFlush(new SqlFrame(SqlOpcode.ERROR, frame.requestId(),
					SqlWire.error(SqlWireErrorCodes.UNKNOWN_SESSION,
							"unknown sessionId " + exec.sessionId())));
			return;
		}
		final int requestId = frame.requestId();
		inFlightExec.add(requestId);
		final SerialTaskQueue queue = sessionQueues.computeIfAbsent(exec.sessionId(), k -> new SerialTaskQueue());
		queue.submit(ThreadService.getLogicExecutor(), () -> {
			interruptRegistry.register(requestId);
			try {
				if (isCancelled(requestId)) {
					finishRequest(requestId);
					return;
				}
				final SqlResult result = engine.execute(session, exec.sql(), exec.binds());
				writeResult(ctx, requestId, result, null);
			} catch (Exception ex) {
				SqlExecDispatchSupport.finishCancelledOrWriteError(
						ctx, requestId, ex, this::consumeCancelIntent, this::finishRequest, this::serverMeta);
			} finally {
				interruptRegistry.unregister(requestId);
			}
		});
	}

	/**
	 * BATCH_EXEC: run statements serially on the session queue; each result uses the same
	 * ROW_DESC/ROW_DATA/EXEC_DONE (+ FETCH portal) path as EXEC. Fail-fast on error.
	 * Open TX on the session is shared; otherwise each statement is autocommit.
	 */
	private void handleBatchExec(ChannelHandlerContext ctx, SqlFrame frame) {
		if (!requireAuth(ctx, frame)) {
			return;
		}
		final SqlWire.BatchExecPayload batch = SqlWire.readBatchExec(frame.payload());
		final SqlSession session = sessions.get(batch.sessionId());
		if (session == null) {
			ctx.writeAndFlush(new SqlFrame(SqlOpcode.ERROR, frame.requestId(),
					SqlWire.error(SqlWireErrorCodes.UNKNOWN_SESSION,
							"unknown sessionId " + batch.sessionId())));
			return;
		}
		final List<String> sqls = batch.sqls();
		if (sqls.isEmpty()) {
			return;
		}
		final int requestId = frame.requestId();
		inFlightExec.add(requestId);
		final SerialTaskQueue queue = sessionQueues.computeIfAbsent(batch.sessionId(), k -> new SerialTaskQueue());
		queue.submit(ThreadService.getLogicExecutor(),
				() -> runBatchStep(ctx, requestId, session, queue, sqls, 0));
	}

	private void runBatchStep(
			ChannelHandlerContext ctx,
			int requestId,
			SqlSession session,
			SerialTaskQueue queue,
			List<String> sqls,
			int index
	) {
		if (index >= sqls.size()) {
			finishRequest(requestId);
			return;
		}
		if (isCancelled(requestId)) {
			finishRequest(requestId);
			return;
		}
		interruptRegistry.register(requestId);
		try {
			final SqlResult result = engine.execute(session, sqls.get(index));
			final Runnable next = () -> queue.submit(ThreadService.getLogicExecutor(),
					() -> runBatchStep(ctx, requestId, session, queue, sqls, index + 1));
			writeResult(ctx, requestId, result, index + 1 < sqls.size() ? next : null);
		} catch (Exception ex) {
			SqlExecDispatchSupport.finishCancelledOrWriteError(
					ctx, requestId, ex, this::consumeCancelIntent, this::finishRequest, this::serverMeta);
		} finally {
			interruptRegistry.unregister(requestId);
		}
	}

	private void handleFetch(ChannelHandlerContext ctx, SqlFrame frame) {
		if (!requireAuth(ctx, frame)) {
			return;
		}
		final int n = SqlWire.readFetch(frame.payload());
		final Portal portal = portals.get(frame.requestId());
		if (portal == null || portal.cancelled) {
			return;
		}
		if (n <= 0) {
			return;
		}
		ctx.executor().execute(() -> {
			final Portal p = portals.get(frame.requestId());
			if (p == null || p.cancelled) {
				return;
			}
			final int sent = SqlExecDispatchSupport.writeRowWindow(ctx, frame.requestId(), p.rows, p.index, n);
			p.index += sent;
			if (p.index >= p.rows.size()) {
				dropPortal(frame.requestId());
				ctx.writeAndFlush(new SqlFrame(SqlOpcode.EXEC_DONE, frame.requestId(),
						SqlWire.execDone(p.rows.size(), p.tag)));
				final Runnable cont = p.onComplete;
				if (cont != null) {
					cont.run();
				} else {
					finishRequest(frame.requestId());
				}
			} else {
				ctx.flush();
			}
		});
	}

	/**
	 * Idempotent CANCEL: drops portal if open; marks cancel-intent only while request is in-flight.
	 * Safe after FETCH drain or a prior CANCEL — no portal / cancel-intent leak; always ACK.
	 */
	private void handleCancel(ChannelHandlerContext ctx, SqlFrame frame) {
		if (!requireAuth(ctx, frame)) {
			return;
		}
		final int requestId = frame.requestId();
		final Portal removed = dropPortal(requestId);
		final boolean activeRequest = removed != null || inFlightExec.contains(requestId);
		DistributedQueryMetrics.recordCancel(activeRequest);
		if (removed != null) {
			// Abort open stream (and any batch continuation); do not run onComplete.
			finishRequest(requestId);
		} else if (inFlightExec.contains(requestId)) {
			cancelledBeforePortal.put(requestId, Boolean.TRUE);
			interruptRegistry.interrupt(requestId);
		}
		ctx.writeAndFlush(new SqlFrame(SqlOpcode.EXEC_DONE, frame.requestId(),
				SqlWire.execDone(0L, "CANCEL")));
	}

	/**
	 * Single place to remove an open portal. Marks it cancelled so in-flight FETCH is a no-op.
	 *
	 * @return removed portal, or {@code null} if none
	 */
	private Portal dropPortal(int requestId) {
		final Portal removed = portals.remove(requestId);
		if (removed != null) {
			removed.cancelled = true;
		}
		return removed;
	}

	private boolean isCancelled(int requestId) {
		return cancelledBeforePortal.containsKey(requestId);
	}

	private boolean consumeCancelIntent(int requestId) {
		return cancelledBeforePortal.remove(requestId) != null;
	}

	private void finishRequest(int requestId) {
		inFlightExec.remove(requestId);
		cancelledBeforePortal.remove(requestId);
	}

	private boolean requireAuth(ChannelHandlerContext ctx, SqlFrame frame) {
		if (authed.get()) {
			return true;
		}
		final boolean catalogOpen = engine.privileges().isOpen();
		final boolean staticOpen = expectedUser.isEmpty() && expectedPassword.isEmpty();
		if (catalogOpen && staticOpen) {
			return true;
		}
		ctx.writeAndFlush(new SqlFrame(SqlOpcode.ERROR, frame.requestId(),
				SqlWire.error(SqlWireErrorCodes.NOT_AUTHENTICATED, "not authenticated")));
		return false;
	}

	private byte[] execError(Exception ex) {
		return SqlExecDispatchSupport.execErrorPayload(ex, serverMeta());
	}

	private ServerMeta serverMeta() {
		final Boolean lagOverride = applyLagStaleOverride == null ? null : applyLagStaleOverride.get();
		if (replication == null || !replication.isEnabled() || replication.getNodeState() == null) {
			// Solo / peer-replication off: this TCP node is the writer; EMPTY (ineligible)
			// must not be advertised — clients treat ineligible as fence+rediscover.
			if (Boolean.TRUE.equals(lagOverride)) {
				return new ServerMeta(STALE_OVERRIDE_NODE_ID, false, "", true, 0L);
			}
			return ServerMeta.SOLO_WRITER;
		}
		try {
			final boolean applyLagStale = lagOverride != null
					? lagOverride.booleanValue()
					: replication.isApplyLagStale();
			return new ServerMeta(
					replication.getNodeState().getNodeId(),
					replication.isWriterEligible(),
					replication.promoteHint(),
					applyLagStale,
					replication.getNodeState().getSchemaEpoch(),
					replication.regionEpoch(),
					replication.regionRoleWire());
		} catch (RuntimeException ignored) {
			if (Boolean.TRUE.equals(lagOverride)) {
				return new ServerMeta(STALE_OVERRIDE_NODE_ID, false, "", true, 0L);
			}
			return ServerMeta.SOLO_WRITER;
		}
	}

	/**
	 * Push {@link SqlOpcode#PROMOTE_NOTIFY} with current {@link ServerMeta} (same payload as AUTH_OK).
	 * <p>
	 * Called by {@link SqlChannelRegistry} after a coordinator re-rank. Uses requestId {@code 0}
	 * (server-initiated) and only notifies authenticated active channels.
	 */
	@VisibleForTesting
	void pushPromoteNotify(ChannelHandlerContext ctx) {
		if (!authed.get() || ctx == null || ctx.channel() == null || !ctx.channel().isActive()) {
			return;
		}
		ctx.writeAndFlush(new SqlFrame(
				SqlOpcode.PROMOTE_NOTIFY,
				SERVER_PUSH_REQUEST_ID,
				SqlWire.serverMeta(serverMeta())
		));
	}

	/**
	 * Write one statement outcome using the EXEC stream framing.
	 *
	 * @param onComplete optional continuation after EXEC_DONE (batch next step); invoked off EL via queue
	 */
	private void writeResult(ChannelHandlerContext ctx, int requestId, SqlResult result, Runnable onComplete) {
		ctx.executor().execute(() -> {
			if (consumeCancelIntent(requestId)) {
				finishRequest(requestId);
				return;
			}
			if (result.kind() == SqlResult.Kind.RESULT_SET) {
				SqlFrames.write(ctx, SqlOpcode.ROW_DESC, requestId, out -> SqlWire.rowDescInto(out, result.columns()));
				final List<Object[]> rows = result.rows();
				final int sent = SqlExecDispatchSupport.writeRowWindow(ctx, requestId, rows, 0, SqlWire.DEFAULT_FETCH_WINDOW);
				if (sent < rows.size()) {
					portals.put(requestId, new Portal(rows, sent, result.tag(), onComplete));
					ctx.flush();
				} else if (onComplete != null) {
					ctx.writeAndFlush(new SqlFrame(SqlOpcode.EXEC_DONE, requestId,
							SqlWire.execDone(rows.size(), result.tag())));
					onComplete.run();
				} else {
					finishRequest(requestId);
					ctx.writeAndFlush(new SqlFrame(SqlOpcode.EXEC_DONE, requestId,
							SqlWire.execDone(rows.size(), result.tag())));
				}
			} else if (onComplete != null) {
				ctx.writeAndFlush(new SqlFrame(SqlOpcode.EXEC_DONE, requestId,
						SqlWire.execDone(result.rowsAffected(), result.tag())));
				onComplete.run();
			} else {
				finishRequest(requestId);
				ctx.writeAndFlush(new SqlFrame(SqlOpcode.EXEC_DONE, requestId,
						SqlWire.execDone(result.rowsAffected(), result.tag())));
			}
		});
	}

	/**
	 * Open RESULT_SET cursor for a single EXEC / BATCH_EXEC requestId.
	 */
	private static final class Portal {
		private final List<Object[]> rows;
		private int index;
		private final String tag;
		private final Runnable onComplete;
		private volatile boolean cancelled;

		private Portal(List<Object[]> rows, int index, String tag, Runnable onComplete) {
			this.rows = rows;
			this.index = index;
			this.tag = tag;
			this.onComplete = onComplete;
		}
	}
}
