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
import org.genfork.grid.sql.client.ServerMeta;
import org.genfork.grid.sql.client.sync.SyncAwait;
import org.genfork.grid.sql.client.sync.SyncConnection;
import org.genfork.grid.sql.client.sync.SyncSession;
import org.genfork.grid.sql.client.sync.SyncTxContext;

/**
 * Jepsen workload facade over product {@link SyncSession} ({@code grid://} URL).
 * <p>
 * Connect / fence / query plumbing is {@link SyncSession}; this type only maps Elle / Knossos
 * ops onto register / JOIN tables.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
public final class JepsenSqlClient implements AutoCloseable {
	public static final String TABLE = "jepsen_register";
	public static final String TABLE_PARENT = "jepsen_parent";
	public static final String TABLE_CHILD = "jepsen_child";
	/** Parent PK = childId + offset — cross-shard JOIN under defaultShards=8. */
	public static final int PARENT_ID_OFFSET = 100;
	private static final Duration OP_TIMEOUT = Duration.ofSeconds(5);
	private static final String ENV_JOIN_SHARDS = "JEPSEN_JOIN_SHARDS";

	private final SyncSession session;
	private final String gridUrl;
	private final boolean joinShards;

	public JepsenSqlClient(String gridUrl) {
		this(gridUrl, joinShardsFromEnv());
	}

	/**
	 * @param joinShards when true, append/read use parent/child JOIN tables (Jepsen join workload)
	 */
	public JepsenSqlClient(String gridUrl, boolean joinShards) {
		this.gridUrl = gridUrl;
		if (gridUrl != null && gridUrl.contains("readEndpoints")) {
			throw new IllegalArgumentException(
					"JepsenSqlClient requires PRIMARY-only URL (no readEndpoints)");
		}
		this.session = SyncSession.exclusiveFromUrl(gridUrl, OP_TIMEOUT);
		this.joinShards = joinShards;
	}

	private static boolean joinShardsFromEnv() {
		final String joinFlag = System.getenv(ENV_JOIN_SHARDS);
		return joinFlag != null
				&& ("1".equals(joinFlag.trim()) || Boolean.parseBoolean(joinFlag.trim()));
	}

	/**
	 * True when URL pins a single host — Clojure owns multi-node sticky rotate.
	 */
	static boolean isSingleHostGridUrl(String url) {
		return SyncSession.isSingleHostUrl(url);
	}

	public String gridUrl() {
		return gridUrl;
	}

	public ServerMeta lastServerMeta() {
		return session.lastServerMeta();
	}

	public boolean writerEligible() {
		return session.writerEligible();
	}

	public String promoteHint() {
		return session.promoteHint();
	}

	public Map<String, Object> invoke(String f, Object value) {
		try {
			return invoke(JepsenOp.fromToken(f), value);
		} catch (IllegalArgumentException ex) {
			return fail("unknown-f", f);
		}
	}

	public Map<String, Object> invoke(JepsenOp op, Object value) {
		try {
			session.ensureOpen();
		} catch (Exception ex) {
			return mapException(ex, false, op);
		}
		try {
			return session.callWithWriterRediscover(() -> dispatch(op, value));
		} catch (Exception ex) {
			return mapException(ex, true, op);
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
		final SyncConnection conn = session.connection();
		if (joinShards) {
			return readJoinResult(conn.query(joinSelectSql(key)));
		}
		return readResult(conn.query(
				"SELECT number, status FROM " + TABLE + " WHERE id = " + key));
	}

	private Map<String, Object> doRead(SyncTxContext tx, int key) {
		if (joinShards) {
			return readJoinResult(tx.query(joinSelectSql(key)));
		}
		return readResult(tx.query(
				"SELECT number, status FROM " + TABLE + " WHERE id = " + key));
	}

	private static String joinSelectSql(int key) {
		// LEFT JOIN: child without parent must not look like an empty Elle list.
		// p.id null → definite join-parent-missing fail (see readJoinResult).
		return "SELECT c.number, c.status, p.id FROM " + TABLE_CHILD + " c LEFT OUTER JOIN "
				+ TABLE_PARENT + " p ON c.parent_id = p.id WHERE c.id = " + key;
	}

	private Map<String, Object> doAppend(int key, String token) {
		final SyncConnection conn = session.connection();
		if (joinShards) {
			ensureParent(conn, key);
			return requireAffected(conn.executeUpdate(appendUpsertSql(TABLE_CHILD, key, token, true)),
					"append-affected-zero", key);
		}
		return requireAffected(conn.executeUpdate(appendUpsertSql(TABLE, key, token, false)),
				"append-affected-zero", key);
	}

	private Map<String, Object> doAppend(SyncTxContext tx, int key, String token) {
		if (joinShards) {
			ensureParent(tx, key);
			return requireAffected(tx.executeUpdate(appendUpsertSql(TABLE_CHILD, key, token, true)),
					"append-affected-zero", key);
		}
		return requireAffected(tx.executeUpdate(appendUpsertSql(TABLE, key, token, false)),
				"append-affected-zero", key);
	}

	/**
	 * Atomic append: plain {@code UPDATE}+{@code INSERT} wiped prior tokens when UPDATE
	 * returned 0 (map miss / seed race) — unclean-p0 history kept only last INSERT token.
	 * {@code ON CONFLICT DO UPDATE} concatenates instead of replacing the row.
	 */
	private static String appendUpsertSql(String table, int key, String token, boolean child) {
		final String esc = escapeSql(token);
		if (child) {
			return "INSERT INTO " + table
					+ " (id, parent_id, number, status) VALUES ("
					+ key + ", " + (key + PARENT_ID_OFFSET) + ", '" + esc + "', 'jepsen-append') "
					+ "ON CONFLICT (id) DO UPDATE SET number = number || ' ' || '" + esc
					+ "', status = 'jepsen-append'";
		}
		return "INSERT INTO " + table + " (id, number, status) VALUES ("
				+ key + ", '" + esc + "', 'jepsen-append') "
				+ "ON CONFLICT (id) DO UPDATE SET number = number || ' ' || '" + esc
				+ "', status = 'jepsen-append'";
	}

	private void ensureParent(SyncConnection conn, int key) {
		conn.executeUpdate(parentUpsertSql(key));
	}

	private void ensureParent(SyncTxContext tx, int key) {
		tx.executeUpdate(parentUpsertSql(key));
	}

	/**
	 * Atomic parent ensure: plain UPDATE0+INSERT raced under joinShards and could leave
	 * child rows without a stable parent mid-failover (PR13-M join path).
	 */
	private static String parentUpsertSql(int key) {
		final int parentId = key + PARENT_ID_OFFSET;
		return "INSERT INTO " + TABLE_PARENT + " (id, name) VALUES ("
				+ parentId + ", 'p" + key + "') "
				+ "ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name";
	}

	private static Map<String, Object> readResult(List<Object[]> rows) {
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

	/**
	 * Join-shards read: empty child → empty Elle list; child without parent → :fail
	 * (never :ok null — that forged G-single / G2 against prior appends).
	 */
	private static Map<String, Object> readJoinResult(List<Object[]> rows) {
		if (rows.isEmpty()) {
			return ok(null);
		}
		final Object[] row = rows.getFirst();
		final Object parentPk = row.length > 2 ? row[2] : null;
		if (parentPk == null) {
			return fail("join-parent-missing", null);
		}
		final String number = row[0] == null ? "" : String.valueOf(row[0]);
		final Object status = row.length > 1 ? row[1] : null;
		if (number.isEmpty() && (status == null || "seed".equals(String.valueOf(status)))) {
			return ok(null);
		}
		return ok(number);
	}

	private Map<String, Object> doWrite(int key, String value) {
		final SyncConnection conn = session.connection();
		// Atomic replace: UPDATE0+INSERT wiped concurrent register values (same class as
		// unclean-p0 append before ON CONFLICT). Knossos needs last-write-wins without fork.
		// Fail-closed on affected=0: never forge :ok when the upsert did not touch a row
		// (unclean-p0 / GHA-I ok-append→ok-read-nil class).
		return requireAffected(conn.executeUpdate(
				"INSERT INTO " + TABLE + " (id, number, status) VALUES ("
						+ key + ", '" + escapeSql(value) + "', 'jepsen-write') "
						+ "ON CONFLICT (id) DO UPDATE SET number = EXCLUDED.number, "
						+ "status = 'jepsen-write'"),
				"write-affected-zero", key);
	}

	/**
	 * Elle/Knossos must not see :ok when DML affected zero rows (forged ack → seed nil read).
	 */
	private static Map<String, Object> requireAffected(long affected, String error, int key) {
		if (affected <= 0L) {
			return fail(error, Integer.valueOf(key));
		}
		return ok(null);
	}

	private Map<String, Object> doTxn(Object value) {
		if (!(value instanceof List<?> mops) || mops.isEmpty()) {
			return fail("unknown-mop", value);
		}
		final SyncTxContext tx = session.begin();
		final List<Object> out = new ArrayList<>(mops.size());
		try {
			for (Object mop : mops) {
				if (!(mop instanceof List<?> triple) || triple.size() < 2) {
					tx.rollback();
					return fail("unknown-mop", mop);
				}
				final String opType = String.valueOf(triple.get(0));
				final int k = ((Number) triple.get(1)).intValue();
				if ("r".equals(opType) || ":r".equals(opType)) {
					final Map<String, Object> read = doRead(tx, k);
					if (!"ok".equals(read.get("type"))) {
						tx.rollback();
						return read;
					}
					final List<Object> resultMop = new ArrayList<>(3);
					resultMop.add(":r");
					resultMop.add(k);
					resultMop.add(tokensFromBody(read.get("value")));
					out.add(resultMop);
					continue;
				}
				if ("append".equals(opType) || ":append".equals(opType)) {
					final Object arg = triple.size() > 2 ? triple.get(2) : null;
					final Map<String, Object> append = doAppend(tx, k, stringValue(arg));
					if (!"ok".equals(append.get("type"))) {
						tx.rollback();
						return append;
					}
					final List<Object> resultMop = new ArrayList<>(3);
					resultMop.add(":append");
					resultMop.add(k);
					resultMop.add(arg);
					out.add(resultMop);
					continue;
				}
				tx.rollback();
				return fail("unknown-mop", mop);
			}
			tx.commit();
			return ok(out);
		} catch (RuntimeException ex) {
			try {
				tx.rollback();
			} catch (Exception ignored) {
				// rollback best-effort
			}
			throw ex;
		}
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
				|| root instanceof SyncAwait.SyncTimeoutException
				|| msg.toLowerCase().contains("timeout")) {
			final Map<String, Object> m = new LinkedHashMap<>();
			m.put("type", op == JepsenOp.READ ? "fail" : "info");
			m.put("error", "timeout");
			return m;
		}
		if (name.contains("Connect") || msg.toLowerCase().contains("connect")
				|| msg.contains("SQL channel closed") || msg.contains("not connected")) {
			session.invalidate();
			final Map<String, Object> m = new LinkedHashMap<>();
			final boolean mutate = op == JepsenOp.WRITE || op == JepsenOp.APPEND || op == JepsenOp.TXN;
			final String lower = msg.toLowerCase();
			final boolean definiteMiss = lower.contains("refused")
					|| lower.contains("not connected")
					|| lower.contains("no route")
					|| name.contains("ConnectException");
			m.put("type", requestMayHaveStarted && mutate && !definiteMiss ? "info" : "fail");
			m.put("error", "connect");
			return m;
		}
		if (WriterFenceSignals.requiresWriterRediscover(root)
				|| WriterFenceSignals.requiresWriterRediscover(ex)) {
			// callWithWriterRediscover already rediscovered+retried; drop held channel only.
			session.invalidate();
			final Map<String, Object> m = new LinkedHashMap<>();
			m.put("type", "fail");
			m.put("error", List.of("orchid-not-synced", name, msg));
			return m;
		}
		// Definite schema/column miss — never :info (Elle would treat as crash → false valid).
		if (JepsenErrorClassifyUtil.isDefiniteSchemaError(msg)) {
			return fail(JepsenErrorClassifyUtil.ERR_SCHEMA, msg);
		}
		final Map<String, Object> m = new LinkedHashMap<>();
		m.put("type", "info");
		m.put("error", msg);
		return m;
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
		session.close();
	}
}