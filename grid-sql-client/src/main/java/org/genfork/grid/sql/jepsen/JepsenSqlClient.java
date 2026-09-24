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
package org.genfork.grid.sql.jepsen;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.genfork.grid.common.WriterFenceSignals;
import org.genfork.grid.sql.client.Connection;
import org.genfork.grid.sql.client.RemoteConnection;
import org.genfork.grid.sql.client.RemoteConnectionFactory;
import org.genfork.grid.sql.client.Result;
import org.genfork.grid.sql.client.Row;
import org.genfork.grid.sql.client.RowMetadata;
import org.genfork.grid.sql.client.ServerMeta;

/**
 * Sync Jepsen harness facade over {@link RemoteConnectionFactory} ({@code grid://} URL).
 * Blocking is intentional on this test-harness boundary (not library Reactor paths).
 * <p>
 * Uses reactive SPI ({@code obtain()} Mono + {@code ReactiveExecExchange}); {@code .block()}
 * only at this harness edge — never Sync* / {@code obtainStage} and never a CF→Mono bridge.
 * <p>
 * Workload semantics match example-app {@code JepsenRegisterController}: table
 * {@code jepsen_register(id, number, status)}.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
public final class JepsenSqlClient implements AutoCloseable {
	public static final String TABLE = "jepsen_register";
	private static final Duration OP_TIMEOUT = Duration.ofSeconds(5);

	private final RemoteConnectionFactory factory;
	private final String gridUrl;
	private volatile Connection connection;

	public JepsenSqlClient(String gridUrl) {
		this.gridUrl = gridUrl;
		if (gridUrl != null && gridUrl.contains("readEndpoints")) {
			throw new IllegalArgumentException(
					"JepsenSqlClient requires PRIMARY-only URL (no readEndpoints)");
		}
		this.factory = RemoteConnectionFactory.fromUrl(gridUrl);
	}

	public String gridUrl() {
		return gridUrl;
	}

	/** Last proposer metadata observed over AUTH or ERROR. */
	public ServerMeta lastServerMeta() {
		return factory.lastServerMeta();
	}

	/** Connects when needed, then reports wire-discovered writer eligibility. */
	public boolean writerEligible() {
		ensureConnected();
		return factory.writerEligible();
	}

	/** Connects when needed, then returns the wire-discovered proposer hint. */
	public String promoteHint() {
		ensureConnected();
		return factory.promoteHint();
	}

	/**
	 * Clojure/string entry: parses {@code :f} token into {@link JepsenOp}.
	 * Returns a map with keys {@code type} ({@code ok}|{@code fail}|{@code info}), optional
	 * {@code value}, optional {@code error}.
	 */
	public Map<String, Object> invoke(String f, Object value) {
		try {
			return invoke(JepsenOp.fromToken(f), value);
		} catch (IllegalArgumentException ex) {
			return fail("unknown-f", f);
		}
	}

	public Map<String, Object> invoke(JepsenOp op, Object value) {
		try {
			ensureConnected();
		} catch (Exception ex) {
			return mapException(ex, false, op);
		}
		try {
			return dispatch(op, value);
		} catch (Exception ex) {
			if (!WriterFenceSignals.requiresWriterRediscover(ex)) {
				return mapException(ex, true, op);
			}
			invalidateConnection();
			try {
				factory.rediscoverWriter().block(OP_TIMEOUT);
			} catch (Exception ignored) {
				// rediscover best-effort
			}
			try {
				ensureConnected();
				return dispatch(op, value);
			} catch (Exception retryEx) {
				return mapException(retryEx, true, op);
			}
		}
	}

	private Map<String, Object> dispatch(JepsenOp op, Object value) {
		return switch (op) {
			case READ -> doRead(1);
			case WRITE -> doWrite(1, stringValue(value));
			case APPEND -> doAppend(1, stringValue(value));
			case TXN -> doTxn(value);
		};
	}

	private Map<String, Object> doRead(int key) {
		final String sql = "SELECT number, status FROM " + TABLE + " WHERE id = " + key;
		final List<Object[]> rows = queryRows(sql);
		if (rows.isEmpty()) {
			return ok(null);
		}
		final Object[] row = rows.getFirst();
		final String number = row[0] == null ? "" : String.valueOf(row[0]);
		final Object status = row.length > 1 ? row[1] : null;
		if (number.isEmpty() && (status == null || "seed".equals(String.valueOf(status)))) {
			return ok(null);
		}
		return ok(number);
	}

	private Map<String, Object> doWrite(int key, String value) {
		final String updateSql = "UPDATE " + TABLE + " SET number = '" + escapeSql(value)
				+ "', status = 'jepsen-write' WHERE id = " + key;
		final long updated = exec(updateSql);
		if (updated == 0) {
			exec("INSERT INTO " + TABLE + " (id, number, status) VALUES ("
					+ key + ", '" + escapeSql(value) + "', 'jepsen-write')");
		}
		return ok(null);
	}

	private Map<String, Object> doAppend(int key, String token) {
		final String updateSql = "UPDATE " + TABLE + " SET number = number || ' ' || '"
				+ escapeSql(token) + "' WHERE id = " + key;
		final long updated = exec(updateSql);
		if (updated == 0) {
			exec("INSERT INTO " + TABLE + " (id, number, status) VALUES ("
					+ key + ", '" + escapeSql(token) + "', 'jepsen-append')");
		}
		return ok(null);
	}

	private Map<String, Object> doTxn(Object value) {
		if (!(value instanceof List<?> mops) || mops.isEmpty()) {
			return fail("unknown-mop", value);
		}
		final Object mop = mops.getFirst();
		if (!(mop instanceof List<?> triple) || triple.size() < 2) {
			return fail("unknown-mop", mop);
		}
		final String opType = String.valueOf(triple.get(0));
		final int k = ((Number) triple.get(1)).intValue();
		if ("r".equals(opType) || ":r".equals(opType)) {
			final Map<String, Object> read = doRead(k);
			if (!"ok".equals(read.get("type"))) {
				return read;
			}
			final Object raw = read.get("value");
			final List<Object> tokens = tokensFromBody(raw);
			final List<Object> resultMop = new ArrayList<>(3);
			resultMop.add(":r");
			resultMop.add(k);
			resultMop.add(tokens);
			final List<Object> out = new ArrayList<>(1);
			out.add(resultMop);
			return ok(out);
		}
		if ("append".equals(opType) || ":append".equals(opType)) {
			final Object arg = triple.size() > 2 ? triple.get(2) : null;
			final Map<String, Object> append = doAppend(k, stringValue(arg));
			if (!"ok".equals(append.get("type"))) {
				return append;
			}
			final List<Object> resultMop = new ArrayList<>(3);
			resultMop.add(":append");
			resultMop.add(k);
			resultMop.add(arg);
			final List<Object> out = new ArrayList<>(1);
			out.add(resultMop);
			return ok(out);
		}
		return fail("unknown-mop", mop);
	}

	private void ensureConnected() {
		Connection c = connection;
		if (isLive(c)) {
			return;
		}
		synchronized (this) {
			c = connection;
			if (isLive(c)) {
				return;
			}
			connection = null;
			connection = factory.obtain().block(OP_TIMEOUT);
			if (connection == null) {
				throw new IllegalStateException("connect failed");
			}
		}
	}

	private static boolean isLive(Connection c) {
		if (c == null) {
			return false;
		}
		if (c instanceof RemoteConnection rc) {
			return rc.isOpen();
		}
		return true;
	}

	private long exec(String sql) {
		final Long updated = connection.createStatement(sql)
				.execute()
				.concatMap(Result::getRowsUpdated)
				.reduce(0L, Long::sum)
				.block(OP_TIMEOUT);
		return updated == null ? 0L : updated;
	}

	private List<Object[]> queryRows(String sql) {
		return connection.createStatement(sql)
				.execute()
				.concatMap(r -> r.map((Row row, RowMetadata meta) -> {
					final int cols = meta.getColumnCount();
					final Object[] arr = new Object[cols];
					for (int i = 0; i < cols; i++) {
						arr[i] = row.get(i);
					}
					return arr;
				}))
				.collectList()
				.block(OP_TIMEOUT);
	}

	private static List<Object> tokensFromBody(Object raw) {
		if (raw == null) {
			return null;
		}
		if (raw instanceof List<?> list) {
			return new ArrayList<>(list);
		}
		final String s = String.valueOf(raw).trim();
		if (s.isEmpty()) {
			return new ArrayList<>();
		}
		final String[] parts = s.split("\\s+");
		final List<Object> out = new ArrayList<>(parts.length);
        Collections.addAll(out, parts);
		return out;
	}

	private static Map<String, Object> ok(Object value) {
		final Map<String, Object> m = new LinkedHashMap<>();
		m.put("type", "ok");
		if (value != null) {
			m.put("value", value);
		}
		return m;
	}

	private static Map<String, Object> fail(Object error, Object detail) {
		final Map<String, Object> m = new LinkedHashMap<>();
		m.put("type", "fail");
		m.put("error", detail == null ? error : List.of(error, detail));
		return m;
	}

	private Map<String, Object> mapException(Exception ex, boolean requestMayHaveStarted, JepsenOp op) {
		final Throwable root = rootCause(ex);
		final String msg = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
		final String name = root.getClass().getSimpleName();
		if (root instanceof TimeoutException
				|| msg.toLowerCase().contains("timeout")) {
			final Map<String, Object> m = new LinkedHashMap<>();
			// Reads that timed out without a value are definite failures for Knossos.
			if (op == JepsenOp.READ) {
				m.put("type", "fail");
			} else {
				m.put("type", "info");
			}
			m.put("error", "timeout");
			return m;
		}
		// Ambiguous network only when a mutating request may already have left the client.
		if (name.contains("Connect") || msg.toLowerCase().contains("connect")
				|| msg.contains("SQL channel closed") || msg.contains("not connected")) {
			invalidateConnection();
			final Map<String, Object> m = new LinkedHashMap<>();
			final boolean mutate = op == JepsenOp.WRITE || op == JepsenOp.APPEND || op == JepsenOp.TXN;
			if (requestMayHaveStarted && mutate) {
				m.put("type", "info");
			} else {
				m.put("type", "fail");
			}
			m.put("error", "connect");
			return m;
		}
		if (WriterFenceSignals.requiresWriterRediscover(root)
				|| WriterFenceSignals.requiresWriterRediscover(ex)) {
			invalidateConnection();
			try {
				factory.rediscoverWriter().block(OP_TIMEOUT);
			} catch (Exception ignored) {
				// rediscover best-effort; next op reconnects via create()
			}
			final Map<String, Object> m = new LinkedHashMap<>();
			m.put("type", "fail");
			m.put("error", List.of("orchid-not-synced", name, msg));
			return m;
		}
		final Map<String, Object> m = new LinkedHashMap<>();
		m.put("type", "info");
		m.put("error", msg);
		return m;
	}

	private void invalidateConnection() {
		try {
			final Connection c = connection;
			connection = null;
			if (c != null) {
				try {
					c.close().block(Duration.ofSeconds(1));
				} catch (Exception ignored) {
					// best-effort close before sticky rotate
				}
			}
		} catch (Exception ignored) {
			// close is best-effort; factory reconnect performs AUTH rediscovery
		}
	}

	private static Throwable rootCause(Throwable ex) {
		Throwable cur = ex;
		while (cur.getCause() != null && cur.getCause() != cur) {
			cur = cur.getCause();
		}
		return cur;
	}

	private static String stringValue(Object raw) {
		return raw == null ? "" : String.valueOf(raw);
	}

	private static String escapeSql(String s) {
		return s == null ? "" : s.replace("'", "''");
	}

	@Override
	public void close() {
		final Connection c = connection;
		connection = null;
		if (c != null) {
			try {
				c.close().block(OP_TIMEOUT);
			} catch (Exception ignored) {
			}
		}
		factory.dispose();
	}
}