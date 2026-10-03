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
package org.genfork.grid.sql.jepsen.config;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import org.genfork.grid.common.WriterFenceSignals;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.threading.ThreadService;

/**
 * DDL bootstrap for Jepsen register/join tables on profile {@code jepsen}.
 * <p>
 * Non-writer nodes skip DDL quietly until promotion / tip catch-up — then retry so a
 * Hold claim or late phase-rank never serves {@code Unknown table} after sticky pin.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
@Component
@Profile("jepsen")
public class JepsenSqlBootstrap {
	private static final Logger log = LoggerFactory.getLogger(JepsenSqlBootstrap.class);

	private static final String TABLE_REGISTER = "jepsen_register";
	private static final String TABLE_PARENT = "jepsen_parent";
	private static final String TABLE_CHILD = "jepsen_child";

	private static final int SEED_ID_FROM = 2;
	/** Matches Clojure {@code append-keys-join} (2..32) so join reads have parents without first append. */
	private static final int SEED_ID_TO = 32;
	/** Offset so child PK and parent PK hash to different shards (defaultShards=8). */
	private static final int PARENT_ID_OFFSET = 100;
	private static final int RETRY_MAX_ATTEMPTS = 120;
	private static final long RETRY_SLEEP_MS = 500L;

	private final SqlEngine sqlEngine;
	private final ObjectProvider<ReplicationCoordinator> replicationProvider;
	private final AtomicBoolean ready = new AtomicBoolean(false);
	private final AtomicBoolean retryScheduled = new AtomicBoolean(false);

	public JepsenSqlBootstrap(
			SqlEngine sqlEngine,
			ObjectProvider<ReplicationCoordinator> replicationProvider) {
		this.sqlEngine = sqlEngine;
		this.replicationProvider = replicationProvider;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onReady() {
		final ReplicationCoordinator repl = replicationProvider.getIfAvailable();
		if (repl != null) {
			repl.addPromotionListener(this::tryBootstrapSafe);
		}
		tryBootstrapSafe();
		scheduleRetryLoop();
	}

	private void scheduleRetryLoop() {
		if (!retryScheduled.compareAndSet(false, true)) {
			return;
		}
		// Platform I/O pool: long sleep retry must not pin logic VT carriers.
		ThreadService.getNetworkExecutor().execute(() -> {
			for (int attempt = 0; attempt < RETRY_MAX_ATTEMPTS && !ready.get(); attempt++) {
				try {
					Thread.sleep(RETRY_SLEEP_MS);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					return;
				}
				tryBootstrapSafe();
			}
			if (!ready.get()) {
				log.warn("Jepsen SQL DDL bootstrap still incomplete after {} attempts",
						RETRY_MAX_ATTEMPTS);
			}
		});
	}

	private void tryBootstrapSafe() {
		if (ready.get()) {
			return;
		}
		try {
			if (tryBootstrap()) {
				ready.set(true);
			}
		} catch (RuntimeException ex) {
			if (WriterFenceSignals.requiresWriterRediscover(ex)) {
				log.debug("Jepsen DDL deferred (writer fence): {}", ex.toString());
				return;
			}
			log.warn("Jepsen DDL bootstrap attempt failed: {}", ex.toString());
		}
	}

	/**
	 * @return true when register + parent/child exist (seed best-effort)
	 */
	private boolean tryBootstrap() {
		if (!ensureTables()) {
			return false;
		}
		seedRows();
		log.info("Jepsen SQL DDL ready: {} + {}/{} (seeded {}..{})",
				TABLE_REGISTER, TABLE_PARENT, TABLE_CHILD, SEED_ID_FROM, SEED_ID_TO);
		return true;
	}

	private boolean ensureTables() {
		try {
			sqlEngine.execute("CREATE TABLE IF NOT EXISTS " + TABLE_REGISTER + """
					 (
					  id INT PRIMARY KEY,
					  number VARCHAR,
					  status VARCHAR
					)
					""");
			sqlEngine.execute("CREATE TABLE IF NOT EXISTS " + TABLE_PARENT + """
					 (
					  id INT PRIMARY KEY,
					  name VARCHAR
					)
					""");
			sqlEngine.execute("CREATE TABLE IF NOT EXISTS " + TABLE_CHILD + """
					 (
					  id INT PRIMARY KEY,
					  parent_id INT,
					  number VARCHAR,
					  status VARCHAR
					)
					""");
			return true;
		} catch (RuntimeException ex) {
			if (WriterFenceSignals.requiresWriterRediscover(ex)) {
				// Tables may already be present via replicated DDL apply.
				return tablesPresent();
			}
			throw ex;
		}
	}

	private boolean tablesPresent() {
		return sqlEngine.catalog().getStore(TABLE_REGISTER) != null
				&& sqlEngine.catalog().getStore(TABLE_PARENT) != null
				&& sqlEngine.catalog().getStore(TABLE_CHILD) != null;
	}

	private void seedRows() {
		for (int id = SEED_ID_FROM; id <= SEED_ID_TO; id++) {
			try {
				sqlEngine.execute("INSERT INTO " + TABLE_REGISTER
						+ " (id, number, status) VALUES (" + id + ", '', 'seed')");
			} catch (RuntimeException ex) {
				if (WriterFenceSignals.requiresWriterRediscover(ex)) {
					return;
				}
				// already present / conflict
			}
			try {
				final int parentId = id + PARENT_ID_OFFSET;
				sqlEngine.execute("INSERT INTO " + TABLE_PARENT
						+ " (id, name) VALUES (" + parentId + ", 'p" + id + "')");
				sqlEngine.execute("INSERT INTO " + TABLE_CHILD
						+ " (id, parent_id, number, status) VALUES ("
						+ id + ", " + parentId + ", '', 'seed')");
			} catch (RuntimeException ex) {
				if (WriterFenceSignals.requiresWriterRediscover(ex)) {
					return;
				}
			}
		}
	}
}
