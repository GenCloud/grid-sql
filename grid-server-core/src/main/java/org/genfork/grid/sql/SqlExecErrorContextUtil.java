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
package org.genfork.grid.sql;

import org.genfork.grid.exceptions.NonUniqueValueException;
import org.genfork.grid.exceptions.SqlExecException;
import org.genfork.grid.replication.OrchidNotSyncedException;
import org.genfork.grid.sql.tx.LockWaitCancelledException;
import org.genfork.grid.sql.tx.LockWaitTimeoutException;

/**
 * EXEC failure context: truncate/redact SQL+binds and rethrow with enriched message.
 * <p>
 * Typed admission / uniqueness failures keep their runtime type (wireErrorCode / assertThrows).
 * Generic failures become {@link SqlExecException}. No Netty / Reactor / sync.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
public final class SqlExecErrorContextUtil {
	/** Marker substring present after enrichment (idempotent re-entry). */
	public static final String CTX_SEP = " | sql=";

	private static final int MAX_SQL_CHARS = 512;
	private static final int MAX_BIND_CHARS = 64;
	private static final int MAX_BINDS_SHOWN = 16;
	private static final int MAX_MESSAGE_CHARS = 2_048;
	private static final int MESSAGE_BUILDER_HINT = 256;
	private static final String TRUNC_SUFFIX = "…";
	private static final String BINDS_SEP = " binds=";
	private static final String BIND_BYTES_PREFIX = "<bytes:";
	private static final String BIND_BYTES_SUFFIX = ">";
	private static final String BIND_NULL = "null";
	private static final String BINDS_MORE = ",…";
	private static final String NULL_CAUSE = "null";
	private static final char BIND_OPEN = '[';
	private static final char BIND_CLOSE = ']';
	private static final String BIND_SEP = ", ";

	private SqlExecErrorContextUtil() {
	}

	/**
	 * Attach truncated SQL + redacted binds once; preserve well-known exception types.
	 */
	public static RuntimeException enrich(Throwable cause, String sql, Object[] binds) {
		if (cause == null) {
			return new SqlExecException(
					withExecContext(NULL_CAUSE, sql, binds),
					null,
					sql,
					bindCountOf(binds));
		}
		if (alreadyEnriched(cause)) {
			if (cause instanceof RuntimeException runtime) {
				return runtime;
			}
			return new SqlExecException(cause.getMessage(), cause, sql, bindCountOf(binds));
		}
		final String message = withExecContext(baseMessage(cause), sql, binds);
		final int bindCount = bindCountOf(binds);
		if (cause instanceof NonUniqueValueException) {
			return new NonUniqueValueException(message, cause);
		}
		if (cause instanceof SqlReplicaDmlDeniedException) {
			return new SqlReplicaDmlDeniedException(message, cause);
		}
		if (cause instanceof LockWaitTimeoutException) {
			return withCause(new LockWaitTimeoutException(message), cause);
		}
		if (cause instanceof LockWaitCancelledException) {
			return withCause(new LockWaitCancelledException(message), cause);
		}
		if (cause instanceof OrchidNotSyncedException) {
			return withCause(new OrchidNotSyncedException(message), cause);
		}
		if (cause instanceof SecurityException) {
			return new SecurityException(message, cause);
		}
		if (cause instanceof IllegalArgumentException) {
			return new IllegalArgumentException(message, cause);
		}
		if (cause instanceof IllegalStateException) {
			return new IllegalStateException(message, cause);
		}
		return new SqlExecException(message, cause, sql, bindCount);
	}

	/** Walk past nested {@link SqlExecException} wrappers for wire code mapping. */
	public static Throwable unwrap(Throwable ex) {
		Throwable cur = ex;
		while (cur instanceof SqlExecException && cur.getCause() != null) {
			cur = cur.getCause();
		}
		return cur;
	}

	static String baseMessage(Throwable ex) {
		if (ex == null) {
			return NULL_CAUSE;
		}
		final String msg = ex.getMessage();
		if (msg != null && !msg.isEmpty()) {
			return msg;
		}
		return ex.toString();
	}

	static String withExecContext(String base, String sql, Object[] binds) {
		final StringBuilder out = new StringBuilder(MESSAGE_BUILDER_HINT);
		if (base != null) {
			out.append(base);
		}
		out.append(CTX_SEP);
		appendTruncated(out, sql == null ? "" : sql, MAX_SQL_CHARS);
		out.append(BINDS_SEP);
		appendBinds(out, binds);
		if (out.length() > MAX_MESSAGE_CHARS) {
			out.setLength(MAX_MESSAGE_CHARS - TRUNC_SUFFIX.length());
			out.append(TRUNC_SUFFIX);
		}
		return out.toString();
	}

	private static void appendBinds(StringBuilder out, Object[] binds) {
		out.append(BIND_OPEN);
		if (binds == null || binds.length == 0) {
			out.append(BIND_CLOSE);
			return;
		}
		final int shown = Math.min(binds.length, MAX_BINDS_SHOWN);
		for (int i = 0; i < shown; i++) {
			if (i > 0) {
				out.append(BIND_SEP);
			}
			appendBind(out, binds[i]);
		}
		if (binds.length > MAX_BINDS_SHOWN) {
			out.append(BINDS_MORE);
		}
		out.append(BIND_CLOSE);
	}

	private static void appendBind(StringBuilder out, Object bind) {
		if (bind == null) {
			out.append(BIND_NULL);
			return;
		}
		if (bind instanceof byte[] bytes) {
			out.append(BIND_BYTES_PREFIX);
			out.append(bytes.length);
			out.append(BIND_BYTES_SUFFIX);
			return;
		}
		appendTruncated(out, String.valueOf(bind), MAX_BIND_CHARS);
	}

	private static void appendTruncated(StringBuilder out, String raw, int maxChars) {
		if (raw.length() <= maxChars) {
			out.append(raw);
			return;
		}
		out.append(raw, 0, maxChars - TRUNC_SUFFIX.length());
		out.append(TRUNC_SUFFIX);
	}

	private static boolean alreadyEnriched(Throwable cause) {
		if (cause instanceof SqlExecException) {
			return true;
		}
		final String msg = cause.getMessage();
		return msg != null && msg.contains(CTX_SEP);
	}

	private static int bindCountOf(Object[] binds) {
		return binds == null ? 0 : binds.length;
	}

	private static RuntimeException withCause(RuntimeException out, Throwable cause) {
		out.initCause(cause);
		return out;
	}
}
