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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.query.filters.FilterCondition;
import org.genfork.grid.query.filters.WireResidualBatch;
import org.genfork.grid.query.filters.impl.AndCondition;
import org.genfork.grid.query.filters.impl.LogicalOperatorCondition;
import org.genfork.grid.serial.RowEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire residual batch EQ path vs scalar {@link FilterCondition#matches}.
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public class WireResidualBatchIT {
	private static final String TABLE = "wrb_t";
	private static final int ROW_COUNT = 32;
	private static final int MATCH_STATUS = 1;
	private static final int OTHER_STATUS = 0;
	private static final int STATUS_MATCH_EVERY = 2;

	private TableSchema schema;
	private List<byte[]> blobs;
	private LogicalOperatorCondition eqStatus;

	@BeforeEach
	void setUp() {
		schema = TableSchema.builder(TABLE)
				.primaryKey("id", SqlType.INT)
				.column("status", SqlType.INT)
				.build();
		blobs = new ArrayList<>(ROW_COUNT);
		for (int i = 0; i < ROW_COUNT; i++) {
			final int status = (i % STATUS_MATCH_EVERY == 0) ? MATCH_STATUS : OTHER_STATUS;
			blobs.add(RowEncoder.encode(schema, new Object[]{i, status}));
		}
		eqStatus = new LogicalOperatorCondition(
				"status", LogicalOperatorCondition.Operator.EQ, MATCH_STATUS);
		eqStatus.validate(schema);
	}

	@Test
	void filterBlobsMatchesPerRowScalar() {
		assertTrue(WireResidualBatch.isSimpleEq(eqStatus));

		final List<byte[]> batch = WireResidualBatch.filterBlobs(blobs, schema, eqStatus);
		final List<byte[]> scalar = new ArrayList<>();
		for (byte[] blob : blobs) {
			if (eqStatus.matches(blob, schema)) {
				scalar.add(blob);
			}
		}
		assertEquals(scalar.size(), batch.size());
		for (int i = 0; i < scalar.size(); i++) {
			assertTrue(Arrays.equals(scalar.get(i), batch.get(i)));
		}
	}

	@Test
	void andConditionFallsBackToScalarAndStillMatches() {
		final LogicalOperatorCondition eqId = new LogicalOperatorCondition(
				"id", LogicalOperatorCondition.Operator.EQ, 0);
		eqId.validate(schema);
		final FilterCondition and = new AndCondition(eqStatus, eqId);
		and.validate(schema);
		assertFalse(WireResidualBatch.isSimpleEq(and));

		final List<byte[]> batch = WireResidualBatch.filterBlobs(blobs, schema, and);
		assertEquals(1, batch.size());
		assertTrue(and.matches(batch.getFirst(), schema));
	}
}
