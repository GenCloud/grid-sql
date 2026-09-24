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

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.mem.index.AbstractIndexOperation;
import org.genfork.grid.mem.index.IndexPointerRef;
import org.genfork.grid.mem.index.bitmap.GridBitmapIndex;
import org.genfork.grid.mem.index.btree.IndexOperationResult;
import org.genfork.grid.mem.index.btree.SingleTreeKey;
import org.genfork.grid.query.filters.FilterCondition;
import org.genfork.grid.query.filters.impl.AndCondition;
import org.genfork.grid.query.filters.impl.LogicalOperatorCondition;
import org.genfork.grid.query.plan.QueryOptimizer;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.serial.SqlWireUtil;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1.5 bitmap plan polish: IN-list OR + multi-EQ AND intersect via CREATE BITMAP INDEX.
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
class BitmapPlanPolishTest {

	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		final GridConfigurationProperties props = new GridConfigurationProperties();
		props.getDurability().setEnabled(false);
		props.getReplication().setEnabled(false);
		final TableCatalog catalog = new TableCatalog();
		final ReplicationCoordinator replication = new ReplicationCoordinator(props);
		engine = new SqlEngine(catalog, replication, props.getSql().getDefaultShards());
		engine.execute("CREATE TABLE bm_t (id INT PRIMARY KEY, flag INT, color INT)");
		engine.execute("CREATE BITMAP INDEX bm_flag ON bm_t (flag)");
		engine.execute("CREATE BITMAP INDEX bm_color ON bm_t (color)");
		engine.execute("INSERT INTO bm_t VALUES (1, 1, 10), (2, 0, 10), (3, 1, 20), (4, 1, 10), (5, 2, 30)");
	}

	@Test
	void preferBitmapPredicatesKeepsEqAndIn() {
		final GridBitmapIndex flag = new GridBitmapIndex("flag-BITMAP", 64);
		final Map<String, AbstractIndexOperation<byte[], SingleTreeKey>> indexes = new HashMap<>();
		indexes.put("flag", flag);

		final LogicalOperatorCondition eq = new LogicalOperatorCondition(
				"flag", LogicalOperatorCondition.Operator.EQ, 1);
		final LogicalOperatorCondition in = new LogicalOperatorCondition(
				"flag", LogicalOperatorCondition.Operator.IN, 0, 1);
		final FilterCondition and = new AndCondition(eq, in);

		final FilterCondition preferred = QueryOptimizer.preferBitmapPredicates(indexes, and);
		assertInstanceOf(AndCondition.class, preferred);
		final AndCondition andPref = (AndCondition) preferred;
		assertInstanceOf(LogicalOperatorCondition.class, andPref.getLeft());
		assertInstanceOf(LogicalOperatorCondition.class, andPref.getRight());
	}

	@Test
	void bitmapSearchInOrsPostings() {
		final GridBitmapIndex index = new GridBitmapIndex("flag-BITMAP", 64);
		index.insert(new SingleTreeKey(SqlWireUtil.toGenericArray(1)),
				new IndexPointerRef(0L, SqlWireUtil.toGenericArray(1)));
		index.insert(new SingleTreeKey(SqlWireUtil.toGenericArray(2)),
				new IndexPointerRef(0L, SqlWireUtil.toGenericArray(2)));
		index.insert(new SingleTreeKey(SqlWireUtil.toGenericArray(3)),
				new IndexPointerRef(0L, SqlWireUtil.toGenericArray(3)));

		final IndexOperationResult result = index.searchIn(List.of(
				new SingleTreeKey(SqlWireUtil.toGenericArray(1)),
				new SingleTreeKey(SqlWireUtil.toGenericArray(3))
		));
		assertTrue(result.hasBitmap());
		result.expandBitmapPointers();
		assertEquals(2L, result.getSize());
	}

	@Test
	void sqlInAndMultiEqUseBitmapIndexes() {
		final SqlResult inRows = engine.execute("SELECT id FROM bm_t WHERE flag IN (0, 2)");
		assertEquals(2, inRows.rows().size());

		final SqlResult andRows = engine.execute("SELECT id FROM bm_t WHERE flag = 1 AND color = 10");
		assertEquals(2, andRows.rows().size());
	}

	@Test
	void explainAnalyzeSurfacesEstimatedCost() {
		final SqlResult result = engine.execute("EXPLAIN ANALYZE SELECT id FROM bm_t WHERE flag = 1");
		assertFalse(result.rows().isEmpty());
		boolean sawEstCost = false;
		for (Object[] row : result.rows()) {
			final String detail = String.valueOf(row[2]);
			if (detail.contains("estCost=")) {
				sawEstCost = true;
				break;
			}
		}
		assertTrue(sawEstCost, "expected estCost in EXPLAIN ANALYZE detail/SUMMARY");
	}
}