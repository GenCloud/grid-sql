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
package org.genfork.grid.sql.client;

import org.genfork.grid.sql.netty.SqlWire;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Parsed client connection options (URL query / programmatic).
 * Absent {@link Option} values fall back to product defaults via accessors.
 * <p>
 * {@link #minConnections()} — TCP sockets opened on warmup / first {@code obtain}.<br>
 * {@link #maxConnections()} — upper bound of TCP sockets in the factory pool.<br>
 * {@link #maxTxContexts()} — soft backpressure cap on concurrent logical sessions
 * ({@code SESSION_OPEN} / {@link TxContext}) on one TCP (0 = unlimited); not "one TX
 * per socket". Concurrent {@code begin()} and autocommit share the channel; isolation
 * is locks + OpLog {@code TX_*} + prepare handle. Not a substitute for {@code maxConnections}.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public final class ConnectionOptions {
	public static final int DEFAULT_PORT = 15432;
	public static final String DEFAULT_SCHEMA = "public";
	public static final int DEFAULT_MIN_CONNECTIONS = 1;
	public static final int DEFAULT_MAX_CONNECTIONS = 1;
	public static final int DEFAULT_MAX_TX_CONTEXTS = 256;
	public static final boolean DEFAULT_WARMUP = false;
	public static final Duration DEFAULT_EXEC_TIMEOUT = Duration.ZERO;
	public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
	public static final Duration DEFAULT_READ_TIMEOUT = Duration.ZERO;
	public static final Duration DEFAULT_WRITE_TIMEOUT = Duration.ZERO;
	public static final int DEFAULT_MAX_RETRIES = 0;
	public static final Duration DEFAULT_RETRY_DELAY = Duration.ofMillis(200);
	public static final RetryMode DEFAULT_RETRY_MODE = RetryMode.OFF;
	/** Default session / bind timezone ({@code UTC}). */
	public static final String DEFAULT_TIMEZONE = "UTC";
	public static final ReadPreference DEFAULT_READ_PREFERENCE = ReadPreference.PRIMARY;
	public static final StaleReadPolicy DEFAULT_STALE_READ_POLICY = StaleReadPolicy.FAIL_CLOSED;
	public static final int DEFAULT_MAX_READ_CONNECTIONS = 1;
	/** Default FETCH portal window (rows per FETCH). */
	public static final int DEFAULT_FETCH_WINDOW = SqlWire.DEFAULT_FETCH_WINDOW;

	private final Option<Integer> minConnections;
	private final Option<Integer> maxConnections;
	private final Option<Integer> maxTxContexts;
	private final Option<Boolean> warmup;
	private final Option<Duration> execTimeout;
	private final Option<Duration> connectTimeout;
	private final Option<Duration> readTimeout;
	private final Option<Duration> writeTimeout;
	private final Option<Integer> maxRetries;
	private final Option<Duration> retryDelay;
	private final Option<RetryMode> retryMode;
	private final Option<String> timezone;
	private final Option<ReadPreference> readPreference;
	private final Option<StaleReadPolicy> staleReadPolicy;
	private final Option<Integer> maxReadConnections;
	private final Option<Integer> fetchWindow;
	private final List<HostEndpoint> readEndpoints;

	private ConnectionOptions(Builder b) {
		this.minConnections = b.minConnections;
		this.maxConnections = b.maxConnections;
		this.maxTxContexts = b.maxTxContexts;
		this.warmup = b.warmup;
		this.execTimeout = b.execTimeout;
		this.connectTimeout = b.connectTimeout;
		this.readTimeout = b.readTimeout;
		this.writeTimeout = b.writeTimeout;
		this.maxRetries = b.maxRetries;
		this.retryDelay = b.retryDelay;
		this.retryMode = b.retryMode;
		this.timezone = b.timezone;
		this.readPreference = b.readPreference;
		this.staleReadPolicy = b.staleReadPolicy;
		this.maxReadConnections = b.maxReadConnections;
		this.fetchWindow = b.fetchWindow;
		this.readEndpoints = List.copyOf(b.readEndpoints);
	}

	public static Builder builder() {
		return new Builder();
	}

	public static ConnectionOptions defaults() {
		return builder().build();
	}

	public int maxConnections() {
		return Math.max(1, maxConnections.orElse(DEFAULT_MAX_CONNECTIONS));
	}

	/**
	 * Warm / first-obtain TCP count, clamped to {@code [1, maxConnections()]}.
	 */
	public int minConnections() {
		final int max = maxConnections();
		final int raw = minConnections.orElse(DEFAULT_MIN_CONNECTIONS);
		return Math.min(max, Math.max(1, raw));
	}

	public int maxTxContexts() {
		final int v = maxTxContexts.orElse(DEFAULT_MAX_TX_CONTEXTS);
		if (v <= 0) {
			return Integer.MAX_VALUE;
		}
		return v;
	}

	public boolean warmup() {
		return warmup.orElse(DEFAULT_WARMUP);
	}

	public Duration execTimeout() {
		return execTimeout.orElse(DEFAULT_EXEC_TIMEOUT);
	}

	public Duration connectTimeout() {
		return connectTimeout.orElse(DEFAULT_CONNECT_TIMEOUT);
	}

	public Duration readTimeout() {
		return readTimeout.orElse(DEFAULT_READ_TIMEOUT);
	}

	public Duration writeTimeout() {
		return writeTimeout.orElse(DEFAULT_WRITE_TIMEOUT);
	}

	public int maxRetries() {
		return Math.max(0, maxRetries.orElse(DEFAULT_MAX_RETRIES));
	}

	public Duration retryDelay() {
		return retryDelay.orElse(DEFAULT_RETRY_DELAY);
	}

	public RetryMode retryMode() {
		return retryMode.orElse(DEFAULT_RETRY_MODE);
	}

	/** IANA / ZoneId id for bind/result temporal conversion (default {@link #DEFAULT_TIMEZONE}). */
	public String timezone() {
		final String z = timezone.orElse(DEFAULT_TIMEZONE);
		if (z == null || z.isBlank()) {
			return DEFAULT_TIMEZONE;
		}
		return z.trim();
	}

	public ReadPreference readPreference() {
		return readPreference.orElse(DEFAULT_READ_PREFERENCE);
	}

	public StaleReadPolicy staleReadPolicy() {
		return staleReadPolicy.orElse(DEFAULT_STALE_READ_POLICY);
	}

	public int maxReadConnections() {
		return Math.max(1, maxReadConnections.orElse(DEFAULT_MAX_READ_CONNECTIONS));
	}

	/** Rows requested per FETCH while streaming a result set (≥1). */
	public int fetchWindow() {
		return Math.max(1, fetchWindow.orElse(DEFAULT_FETCH_WINDOW));
	}

	/** Separate read ring; empty when unset (PRIMARY-only). */
	public List<HostEndpoint> readEndpoints() {
		return readEndpoints;
	}

	public static final class Builder {
		private Option<Integer> minConnections = Option.none();
		private Option<Integer> maxConnections = Option.none();
		private Option<Integer> maxTxContexts = Option.none();
		private Option<Boolean> warmup = Option.none();
		private Option<Duration> execTimeout = Option.none();
		private Option<Duration> connectTimeout = Option.none();
		private Option<Duration> readTimeout = Option.none();
		private Option<Duration> writeTimeout = Option.none();
		private Option<Integer> maxRetries = Option.none();
		private Option<Duration> retryDelay = Option.none();
		private Option<RetryMode> retryMode = Option.none();
		private Option<String> timezone = Option.none();
		private Option<ReadPreference> readPreference = Option.none();
		private Option<StaleReadPolicy> staleReadPolicy = Option.none();
		private Option<Integer> maxReadConnections = Option.none();
		private Option<Integer> fetchWindow = Option.none();
		private List<HostEndpoint> readEndpoints = List.of();

		public Builder minConnections(int v) {
			this.minConnections = Option.some(v);
			return this;
		}

		public Builder maxConnections(int v) {
			this.maxConnections = Option.some(v);
			return this;
		}

		public Builder maxTxContexts(int v) {
			this.maxTxContexts = Option.some(v);
			return this;
		}

		public Builder warmup(boolean v) {
			this.warmup = Option.some(v);
			return this;
		}

		public Builder execTimeout(Duration v) {
			this.execTimeout = Option.ofNullable(v);
			return this;
		}

		public Builder connectTimeout(Duration v) {
			this.connectTimeout = Option.ofNullable(v);
			return this;
		}

		public Builder readTimeout(Duration v) {
			this.readTimeout = Option.ofNullable(v);
			return this;
		}

		public Builder writeTimeout(Duration v) {
			this.writeTimeout = Option.ofNullable(v);
			return this;
		}

		public Builder maxRetries(int v) {
			this.maxRetries = Option.some(v);
			return this;
		}

		public Builder retryDelay(Duration v) {
			this.retryDelay = Option.ofNullable(v);
			return this;
		}

		public Builder retryMode(RetryMode v) {
			Objects.requireNonNull(v, "retryMode");
			this.retryMode = Option.some(v);
			return this;
		}

		public Builder timezone(String v) {
			this.timezone = Option.ofNullable(v);
			return this;
		}

		public Builder readPreference(ReadPreference v) {
			Objects.requireNonNull(v, "readPreference");
			this.readPreference = Option.some(v);
			return this;
		}

		public Builder staleReadPolicy(StaleReadPolicy v) {
			Objects.requireNonNull(v, "staleReadPolicy");
			this.staleReadPolicy = Option.some(v);
			return this;
		}

		public Builder maxReadConnections(int v) {
			this.maxReadConnections = Option.some(v);
			return this;
		}

		public Builder fetchWindow(int v) {
			this.fetchWindow = Option.some(Math.max(1, v));
			return this;
		}

		public Builder readEndpoints(List<HostEndpoint> endpoints) {
			this.readEndpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
			return this;
		}

		public ConnectionOptions build() {
			return new ConnectionOptions(this);
		}
	}
}
