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
package org.genfork.grid.sql.jmeter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Shared load mix weights / SQL builders for RequestResponse samplers.
 * <p>
 * Capacity mix excludes short-TX (record locks) — max TPS sizing only.
 * Chaos / stress keep short-TX for lock / overload behaviour.
 * {@code READ_ONLY} / {@code WRITE_ONLY} are single-lane capacity stamps.
 * SQL text comes from {@link GridSqlLoadSqlTemplates} (defaults or {@code -J} overrides).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
public final class GridSqlLoadMix {

	public static final String LABEL_EQ_LIMIT = "eq-limit";
	public static final String LABEL_UPSERT = "upsert";
	public static final String LABEL_SHORT_TX = "short-tx";
	public static final String LABEL_COUNT_JOIN = "count-join";

	private GridSqlLoadMix() {
	}

	public static CompletableFuture<Void> startRandomOp(GridSqlJmeterSession session) {
		return pick(session).start(session);
	}

	public static Op pick(GridSqlJmeterSession session) {
		final GridSqlJmeterSession.MixProfile profile = session.mixProfile();
		final GridSqlLoadSqlTemplates sql = session.sqlTemplates();
		final int pick = ThreadLocalRandom.current().nextInt(profile.weightTotal());
		final int key = session.nextKey();
		if (pick < profile.weightEqLimit()) {
			final String eqSql = sql.renderEqLimit(key);
			return new Op(LABEL_EQ_LIMIT, "EQ+LIMIT id=" + key, s -> s.startQuery(eqSql));
		}
		final int afterEq = profile.weightEqLimit() + profile.weightUpsert();
		if (pick < afterEq) {
			final int batchSize = session.writeBatchSize();
			if (profile == GridSqlJmeterSession.MixProfile.WRITE_ONLY && batchSize > 1) {
				return new Op(
						LABEL_UPSERT,
						"UPSERT_BATCH n=" + batchSize,
						batchSize,
						s -> s.startWriteBatch(batchSize));
			}
			final String val = "u-" + key + "-" + ThreadLocalRandom.current().nextInt(1_000_000);
			final String upsertSql = sql.renderUpsert(key, val);
			return new Op(LABEL_UPSERT, "UPSERT id=" + key, s -> s.startUpdate(upsertSql));
		}
		final int afterUpsert = afterEq + profile.weightShortTx();
		if (profile.weightShortTx() > 0 && pick < afterUpsert) {
			return new Op(LABEL_SHORT_TX, "SHORT_TX id=" + key, s -> s.startShortTx(key));
		}
		final String joinSql = sql.renderCountJoin(key);
		return new Op(LABEL_COUNT_JOIN, "COUNT/JOIN a_id=" + key, s -> s.startQuery(joinSql));
	}

	@FunctionalInterface
	public interface OpStarter {
		CompletableFuture<Void> start(GridSqlJmeterSession session);
	}

	public record Op(String label, String detail, int sampleCount, OpStarter starter) {
		public Op(String label, String detail, OpStarter starter) {
			this(label, detail, 1, starter);
		}

		public CompletableFuture<Void> start(GridSqlJmeterSession session) {
			return starter.start(session);
		}
	}
}
