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
package index.unit.query;

import index.sql.SqlBenchHelper;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.query.plan.ExplainQuery;
import org.genfork.grid.query.plan.QueryParser;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.store.TableStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;

/**
 * SQL-first fixture: {@link TableSchema} + {@link TableStore} + index selectKeys.
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
public abstract class AbstractQueriesTest {
	protected SqlEngine engine;
	protected TableStore store;
	protected TableSchema schema;

	protected abstract TableSchema buildSchema();

	protected abstract int fillCount();

	protected abstract void fillRows();

	@BeforeAll
	public void setup() {
		QueryParser.clearPlanCache();
		engine = SqlBenchHelper.createEngine(4);
		schema = buildSchema();
		SqlBenchHelper.ensureIndexedTable(engine, schema);
		store = SqlBenchHelper.store(engine, schema.tableName());
		fillRows();
		store.index().startAnalyze();
	}

	@AfterAll
	public void destroy() {
		if (engine != null && schema != null && engine.catalog().exists(schema.tableName())) {
			engine.catalog().dropTable(schema.tableName());
		}
	}

	protected List<Object[]> selectRows(String sql) {
		return store.select(sql, List.of("*"));
	}

	protected List<byte[]> selectKeys(String sql) {
		return store.selectKeys(sql);
	}

	protected List<byte[]> selectKeys(ExplainQuery.QueryPlan plan, String sql) {
		return store.index().executeStatement(plan, sql);
	}
}
