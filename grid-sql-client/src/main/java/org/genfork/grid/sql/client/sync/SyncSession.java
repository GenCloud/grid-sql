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
package org.genfork.grid.sql.client.sync;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.genfork.grid.common.WriterFenceSignals;
import org.genfork.grid.sql.client.GridSqlUri;
import org.genfork.grid.sql.client.ServerMeta;

/**
 * Sticky sync session: held {@link SyncConnection} + writer meta / rediscover helpers.
 * <p>
 * Product convenience edge for CLI / Jepsen / tools — never {@code Mono.block()}.
 * Workload SQL stays in the caller; connect / fence / query plumbing lives here.
 * Channel slot uses CAS (no monitor wait on VT / sync edge).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
public final class SyncSession implements AutoCloseable {
	private final SyncConnectionFactory factory;
	private final boolean writerRediscoverEnabled;
	private final AtomicReference<SyncConnection> connection = new AtomicReference<>();

	public SyncSession(SyncConnectionFactory factory) {
		this(factory, true);
	}

	public SyncSession(SyncConnectionFactory factory, boolean writerRediscoverEnabled) {
		this.factory = Objects.requireNonNull(factory, "factory");
		this.writerRediscoverEnabled = writerRediscoverEnabled;
	}

	/**
	 * Exclusive product-URL session (no JDBC TCP floor). Multi-host enables writer rediscover.
	 */
	public static SyncSession exclusiveFromUrl(String gridUrl) {
		return exclusiveFromUrl(gridUrl, null);
	}

	public static SyncSession exclusiveFromUrl(String gridUrl, Duration timeout) {
		final SyncConnectionFactory factory = SyncConnectionFactory.exclusiveFromUrl(gridUrl, timeout);
		return new SyncSession(factory, !isSingleHostUrl(gridUrl));
	}

	/**
	 * True when authority pins a single host (or URL blank) — multi-node sticky rotate is external.
	 */
	public static boolean isSingleHostUrl(String url) {
		if (url == null || url.isBlank()) {
			return true;
		}
		return GridSqlUri.parse(url).endpoints().size() <= 1;
	}

	public SyncConnectionFactory factory() {
		return factory;
	}

	public Duration timeout() {
		return factory.timeout();
	}

	public ServerMeta lastServerMeta() {
		return factory.lastServerMeta();
	}

	/** Ensures a live channel, then reports wire writer eligibility. */
	public boolean writerEligible() {
		ensureOpen();
		return factory.writerEligible();
	}

	/** Ensures a live channel, then returns wire promote hint. */
	public String promoteHint() {
		ensureOpen();
		return factory.promoteHint();
	}

	public SyncConnection connection() {
		return ensureOpen();
	}

	public SyncConnection ensureOpen() {
		for (;;) {
			final SyncConnection current = connection.get();
			if (isLive(current)) {
				return current;
			}
			final SyncConnection opened = factory.open();
			if (connection.compareAndSet(current, opened)) {
				parkQuietly(current);
				return opened;
			}
			parkQuietly(opened);
		}
	}

	public void invalidate() {
		final SyncConnection previous = connection.getAndSet(null);
		parkQuietly(previous);
	}

	/**
	 * Mid-op orchid / region fence: drop held channel and AUTH-rediscover writer (multi-host only).
	 */
	public SyncConnection rediscoverWriter() {
		invalidate();
		if (!writerRediscoverEnabled) {
			return ensureOpen();
		}
		final SyncConnection opened = factory.rediscoverWriter();
		connection.set(opened);
		return opened;
	}

	/**
	 * One transparent retry after writer rediscover on orchid / region fence (Elle-safe).
	 */
	public <T> T callWithWriterRediscover(Supplier<T> operation) {
		Objects.requireNonNull(operation, "operation");
		try {
			ensureOpen();
			return operation.get();
		} catch (RuntimeException ex) {
			if (!WriterFenceSignals.requiresWriterRediscover(ex)) {
				throw ex;
			}
			rediscoverWriter();
			return operation.get();
		}
	}

	public List<Object[]> query(String sql) {
		return ensureOpen().query(sql);
	}

	public long update(String sql) {
		return ensureOpen().executeUpdate(sql);
	}

	public SyncTxContext begin() {
		return ensureOpen().begin();
	}

	public SyncStatement statement(String sql) {
		return ensureOpen().statement(sql);
	}

	private static boolean isLive(SyncConnection c) {
		return c != null && c.isOpen();
	}

	private static void parkQuietly(SyncConnection c) {
		if (c == null) {
			return;
		}
		try {
			c.close();
		} catch (RuntimeException ignored) {
			// best-effort park
		}
	}

	@Override
	public void close() {
		invalidate();
		factory.close();
	}
}
