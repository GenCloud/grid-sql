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
package index.sql;

import org.genfork.grid.sql.SqlEngine;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Wait until SELECT row count catches up with async index/map visibility.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlSelectAwait {
	private SqlSelectAwait() {
	}

	public static void awaitRowCount(SqlEngine engine, String selectSql, int expected) {
		awaitRowCount(engine, selectSql, expected, TimeUnit.SECONDS.toNanos(20));
	}

	public static void awaitRowCount(SqlEngine engine, String selectSql, int expected, long timeoutNanos) {
		int last = -1;
		final long deadline = System.nanoTime() + timeoutNanos;
		while (System.nanoTime() < deadline) {
			last = engine.execute(selectSql).rows().size();
			if (last >= expected) {
				return;
			}
			LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
		}
		assertEquals(expected, last, "rows visible via SELECT: " + selectSql);
	}
}