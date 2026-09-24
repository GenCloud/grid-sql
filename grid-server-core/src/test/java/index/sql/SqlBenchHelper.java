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

import org.genfork.grid.catalog.IndexDef;
import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.mem.index.IndexType;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.store.TableStore;

import java.util.List;

/**
 * Shared fixture for SQL-first JMH / compare benches.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlBenchHelper {
	private SqlBenchHelper() {
	}

	public static SqlEngine createEngine(int shards) {
		return createEngine(shards, null);
	}

	public static SqlEngine createEngine(int shards, ReplicationCoordinator replication) {
		return new SqlEngine(new TableCatalog(), replication, shards);
	}

	public static void ensureKvTable(SqlEngine engine, String table) {
		engine.execute("CREATE TABLE IF NOT EXISTS " + table
				+ " (id INT PRIMARY KEY, v VARCHAR)");
	}

	/** Query/sort compare layout: id + bucket + score (external order) + LAX indexes. */
	public static TableSchema queryRowSchema(String table) {
		return TableSchema.builder(table)
				.primaryKey("id", SqlType.VARCHAR)
				.column("bucket", SqlType.INT)
				.column("score", SqlType.INT)
				.externalOrder("score")
				.index(IndexDef.of("idx_bucket", IndexType.LAX, "bucket"))
				.index(IndexDef.of("idx_score", IndexType.LAX, "score"))
				.index(IndexDef.of("idx_bucket_score", IndexType.LAX, "bucket", "score"))
				.build();
	}

	/** Hazelcast IMDG twin layout: id + bucket LAX index. */
	public static TableSchema hzRowSchema(String table) {
		return TableSchema.builder(table)
				.primaryKey("id", SqlType.VARCHAR)
				.column("bucket", SqlType.INT)
				.index(IndexDef.of("idx_bucket", IndexType.LAX, "bucket"))
				.build();
	}

	public static void ensureIndexedTable(SqlEngine engine, TableSchema schema) {
		if (engine.catalog().exists(schema.tableName())) {
			return;
		}
		engine.catalog().createTable(schema);
	}

	public static TableStore store(SqlEngine engine, String table) {
		return engine.catalog().getStore(table);
	}

	/** Stop index/entry workers for every open store (JMH TearDown). */
	public static void closeAllStores(SqlEngine engine) {
		if (engine == null || engine.catalog() == null) {
			return;
		}
		for (String name : engine.catalog().tableNames()) {
			final TableStore s = engine.catalog().getStore(name);
			if (s != null) {
				s.close();
			}
		}
	}

	public static void upsertRow(SqlEngine engine, String table, Object... values) {
		store(engine, table).upsert(values);
	}

	/** Sync map+index put for OSS latency gates (fair vs followup IMDG microbench). */
	public static void putIndexedRow(SqlEngine engine, String table, Object... values) {
		store(engine, table).putIndexed(values);
	}

	public static List<byte[]> selectKeys(SqlEngine engine, String table, String sql) {
		return store(engine, table).selectKeys(sql);
	}
}
