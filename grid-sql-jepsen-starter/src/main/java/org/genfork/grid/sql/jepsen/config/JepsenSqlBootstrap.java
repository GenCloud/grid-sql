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

import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.common.WriterFenceSignals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * DDL bootstrap for Jepsen register table on profile {@code jepsen}.
 * <p>
 * Hold / learner / region-fenced nodes skip DDL quietly (TD-REPL-001) — sticky discovery
 * routes writes to the Active writer.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
@Component
@Profile("jepsen")
public class JepsenSqlBootstrap {
	private static final Logger log = LoggerFactory.getLogger(JepsenSqlBootstrap.class);
	private static final int SEED_ID_FROM = 2;
	private static final int SEED_ID_TO = 16;

	private final SqlEngine sqlEngine;

	public JepsenSqlBootstrap(SqlEngine sqlEngine) {
		this.sqlEngine = sqlEngine;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onReady() {
		try {
			sqlEngine.execute("""
					CREATE TABLE IF NOT EXISTS jepsen_register (
					  id INT PRIMARY KEY,
					  number VARCHAR,
					  status VARCHAR
					)
					""");
		} catch (RuntimeException ex) {
			if (WriterFenceSignals.requiresWriterRediscover(ex)) {
				log.debug("Jepsen DDL skipped on non-writer: {}", ex.toString());
				return;
			}
			throw ex;
		}
		for (int id = SEED_ID_FROM; id <= SEED_ID_TO; id++) {
			try {
				sqlEngine.execute("INSERT INTO jepsen_register (id, number, status) VALUES ("
						+ id + ", '', 'seed')");
			} catch (RuntimeException ex) {
				if (WriterFenceSignals.requiresWriterRediscover(ex)) {
					log.debug("Jepsen seed skipped on non-writer id={}: {}", id, ex.toString());
					return;
				}
				// already present / conflict
			}
		}
		log.info("Jepsen SQL DDL ready: jepsen_register (seeded {}..{})", SEED_ID_FROM, SEED_ID_TO);
	}
}