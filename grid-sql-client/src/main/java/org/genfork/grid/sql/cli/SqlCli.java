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
package org.genfork.grid.sql.cli;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.genfork.grid.sql.client.ConnectionOptions;
import org.genfork.grid.sql.client.GridSqlUri;
import org.genfork.grid.sql.client.RemoteConnectionFactory;
import org.genfork.grid.sql.client.Row;
import org.genfork.grid.sql.client.Savepoint;
import org.genfork.grid.sql.client.sync.SyncConnectionFactory;
import org.genfork.grid.sql.client.sync.SyncPreparedHandle;
import org.genfork.grid.sql.client.sync.SyncResult;
import org.genfork.grid.sql.client.sync.SyncSession;
import org.genfork.grid.sql.client.sync.SyncStatement;
import org.genfork.grid.sql.client.sync.SyncTxContext;

/**
 * Thin interactive SQL CLI over product {@link SyncSession}.
 * <p>
 * Blocking only via {@link org.genfork.grid.sql.client.sync.SyncAwait} / {@code obtainStage} —
 * never {@code Mono.block()}.
 * <p>
 * Args: {@code grid://user:pass@host:port/schema?...} or {@code -h/-p/-u/-P}.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public final class SqlCli {
	private static final String DEFAULT_HOST = "127.0.0.1";
	private static final String CMD_PREPARE = "\\prepare ";
	private static final String CMD_EXECUTE = "\\execute ";
	private static final String CMD_DEALLOCATE = "\\deallocate ";

	private SqlCli() {
	}

	public static void main(String[] args) throws Exception {
		String host = DEFAULT_HOST;
		int port = ConnectionOptions.DEFAULT_PORT;
		String user = "";
		String password = "";
		String gridUrl = null;
		for (int i = 0; i < args.length; i++) {
			final String a = args[i];
			if (a.startsWith(GridSqlUri.SCHEME + "://")) {
				gridUrl = a;
				continue;
			}
			switch (a) {
				case "-h", "--host" -> host = args[++i];
				case "-p", "--port" -> port = Integer.parseInt(args[++i]);
				case "-u", "--user" -> user = args[++i];
				case "-P", "--password" -> password = args[++i];
				default -> {
				}
			}
		}
		final SyncSession session;
		final String display;
		if (gridUrl != null) {
			session = SyncSession.exclusiveFromUrl(gridUrl);
			display = gridUrl;
		} else {
			final RemoteConnectionFactory remote =
					new RemoteConnectionFactory(host, port, user, password);
			session = new SyncSession(SyncConnectionFactory.exclusiveOf(remote));
			display = host + ":" + port;
		}
		SyncTxContext currentTx = null;
		final ConcurrentMap<String, Savepoint> savepoints = new ConcurrentHashMap<>();
		final ConcurrentMap<String, SyncPreparedHandle> prepared = new ConcurrentHashMap<>();
		try (session; BufferedReader reader = new BufferedReader(
				new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
			session.ensureOpen();
			System.out.println("Connected to " + display);
			String line;
			while ((line = reader.readLine()) != null) {
				final String sql = line.trim();
				if (sql.isEmpty() || "quit".equalsIgnoreCase(sql) || "exit".equalsIgnoreCase(sql)) {
					break;
				}
				try {
					final String upper = sql.toUpperCase(Locale.ROOT);
					if (upper.equals("BEGIN") || upper.startsWith("BEGIN ")) {
						if (currentTx != null) {
							System.err.println("ERROR: transaction already open");
							continue;
						}
						currentTx = session.begin();
						savepoints.clear();
						System.out.println("OK BEGIN");
						continue;
					}
					if (upper.equals("COMMIT") || upper.startsWith("COMMIT ")) {
						if (currentTx == null) {
							System.err.println("ERROR: no transaction in progress");
							continue;
						}
						currentTx.commit();
						currentTx = null;
						savepoints.clear();
						System.out.println("OK COMMIT");
						continue;
					}
					if (upper.equals("ROLLBACK") || upper.startsWith("ROLLBACK ")) {
						if (currentTx == null) {
							System.err.println("ERROR: no transaction in progress");
							continue;
						}
						if (upper.startsWith("ROLLBACK TO")) {
							final String name = extractIdentAfter(upper, "ROLLBACK TO SAVEPOINT ", "ROLLBACK TO ");
							final Savepoint sp = savepoints.get(name);
							if (sp == null) {
								System.err.println("ERROR: unknown savepoint: " + name);
								continue;
							}
							currentTx.rollbackTo(sp);
							System.out.println("OK ROLLBACK TO " + name);
							continue;
						}
						currentTx.rollback();
						currentTx = null;
						savepoints.clear();
						System.out.println("OK ROLLBACK");
						continue;
					}
					if (upper.startsWith("SAVEPOINT ")) {
						if (currentTx == null) {
							System.err.println("ERROR: no transaction in progress");
							continue;
						}
						final String name = sql.substring("SAVEPOINT ".length()).trim();
						final Savepoint sp = currentTx.savepoint(name);
						savepoints.put(sp.name(), sp);
						System.out.println("OK SAVEPOINT " + sp.name());
						continue;
					}
					if (upper.startsWith("RELEASE SAVEPOINT ") || upper.startsWith("RELEASE ")) {
						if (currentTx == null) {
							System.err.println("ERROR: no transaction in progress");
							continue;
						}
						final String name = extractIdentAfter(upper, "RELEASE SAVEPOINT ", "RELEASE ");
						final Savepoint sp = savepoints.remove(name);
						if (sp == null) {
							System.err.println("ERROR: unknown savepoint: " + name);
							continue;
						}
						currentTx.release(sp);
						System.out.println("OK RELEASE " + name);
						continue;
					}
					if (sql.regionMatches(true, 0, CMD_PREPARE, 0, CMD_PREPARE.length())) {
						final String rest = sql.substring(CMD_PREPARE.length()).trim();
						final int sp = rest.indexOf(' ');
						if (sp <= 0) {
							System.err.println("ERROR: usage: \\prepare name AS <sql>");
							continue;
						}
						String body = rest.substring(sp).trim();
						if (body.regionMatches(true, 0, "AS ", 0, 3)) {
							body = body.substring(3).trim();
						}
						final String name = rest.substring(0, sp).trim();
						final SyncPreparedHandle handle = session.connection().prepare(name, body);
						prepared.put(handle.name(), handle);
						System.out.println("OK PREPARE " + handle.name());
						continue;
					}
					if (sql.regionMatches(true, 0, CMD_EXECUTE, 0, CMD_EXECUTE.length())) {
						final String name = sql.substring(CMD_EXECUTE.length()).trim();
						final SyncPreparedHandle handle = prepared.get(name);
						if (handle == null) {
							System.err.println("ERROR: unknown prepare: " + name);
							continue;
						}
						printResults(handle.execute());
						continue;
					}
					if (sql.regionMatches(true, 0, CMD_DEALLOCATE, 0, CMD_DEALLOCATE.length())) {
						final String name = sql.substring(CMD_DEALLOCATE.length()).trim();
						final SyncPreparedHandle handle = prepared.remove(name);
						if (handle != null) {
							handle.deallocate();
						} else {
							session.connection().deallocate(name);
						}
						System.out.println("OK DEALLOCATE " + name);
						continue;
					}
					final SyncStatement stmt = currentTx != null
							? currentTx.statement(sql)
							: session.statement(sql);
					printResults(stmt.execute());
				} catch (Exception ex) {
					System.err.println("ERROR: " + ex.getMessage());
				}
			}
			if (currentTx != null) {
				try {
					currentTx.rollback();
				} catch (Exception ignored) {
					// best-effort
				}
			}
			for (SyncPreparedHandle h : prepared.values()) {
				try {
					h.deallocate();
				} catch (Exception ignored) {
					// best-effort
				}
			}
		}
	}

	private static void printResults(List<SyncResult> results) {
		if (results == null || results.isEmpty()) {
			return;
		}
		for (SyncResult result : results) {
			if (result.isResultSet() && result.rowMetadata() != null) {
				final int cols = result.rowMetadata().getColumnCount();
				for (Row row : result.rows()) {
					System.out.println(formatRow(row, cols));
				}
			} else {
				System.out.println("OK rowsAffected=" + result.rowsUpdated());
			}
		}
	}

	private static String extractIdentAfter(String upper, String primary, String fallback) {
		String rest;
		if (upper.startsWith(primary)) {
			rest = upper.substring(primary.length()).trim();
		} else if (upper.startsWith(fallback)) {
			rest = upper.substring(fallback.length()).trim();
		} else {
			rest = upper;
		}
		final int sp = rest.indexOf(' ');
		return sp < 0 ? rest : rest.substring(0, sp);
	}

	private static String formatRow(Row row, int cols) {
		final StringBuilder sb = new StringBuilder();
		for (int i = 0; i < cols; i++) {
			if (i > 0) {
				sb.append(" | ");
			}
			sb.append(row.get(i));
		}
		return sb.toString();
	}
}