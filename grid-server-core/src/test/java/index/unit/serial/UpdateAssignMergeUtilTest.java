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
package index.unit.serial;

import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.query.plan.UpdatePlan;
import org.genfork.grid.replication.apply.FieldModifyApplicator;
import org.genfork.grid.replication.codec.ModifyPayload;
import org.genfork.grid.replication.codec.ModifyPayload.Decoded;
import org.genfork.grid.serial.ModifyLiteralArgUtil;
import org.genfork.grid.serial.UpdateAssignMergeUtil;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KIND_LITERAL_SET encode/decode and UpdateAssignMergeUtil conflict checks.
 *
 * @author: GenCloud
 * @date: 2025/06
 * @since: 1.0
 */
class UpdateAssignMergeUtilTest {
	@Test
	void literalArgRoundTripNullAndInt() {
		assertEquals(ModifyPayload.LITERAL_SET_ARG_NULL, ModifyLiteralArgUtil.encode(null));
		assertNull(ModifyLiteralArgUtil.decode(ModifyPayload.LITERAL_SET_ARG_NULL, Integer.class));
		assertEquals("42", ModifyLiteralArgUtil.encode(42));
		assertEquals(42, ((Number) ModifyLiteralArgUtil.decode("42", Integer.class)).intValue());
	}

	@Test
	void applicatorLiteralSetIgnoresPrev() {
		final Object next = FieldModifyApplicator.applyValue(
				String.class, ModifyPayload.KIND_LITERAL_SET, "updated", "old");
		assertEquals("updated", next);
		assertNull(FieldModifyApplicator.applyValue(
				String.class, ModifyPayload.KIND_LITERAL_SET, ModifyPayload.LITERAL_SET_ARG_NULL, "x"));
	}

	@Test
	void mergeRmwAndLiterals() {
		final TableSchema schema = schema();
		final UpdatePlan plan = new UpdatePlan(
				"m",
				List.of(new UpdatePlan.FieldAssign("n", ModifyPayload.KIND_NUMERIC_ADD, "10")),
				"id",
				1,
				"t");
		final Map<String, Object> lits = new LinkedHashMap<>();
		lits.put("v", "updated");
		final List<Decoded> decoded = UpdateAssignMergeUtil.toDecoded(plan, lits, schema);
		assertEquals(2, decoded.size());
		assertEquals(ModifyPayload.KIND_NUMERIC_ADD, decoded.get(0).kind());
		assertEquals(ModifyPayload.KIND_LITERAL_SET, decoded.get(1).kind());
		assertEquals("updated", decoded.get(1).arg());
	}

	@Test
	void duplicateColumnFails() {
		final TableSchema schema = schema();
		final UpdatePlan plan = new UpdatePlan(
				"m",
				List.of(new UpdatePlan.FieldAssign("n", ModifyPayload.KIND_NUMERIC_ADD, "1")),
				"id",
				1,
				"t");
		final Map<String, Object> lits = Map.of("n", 99);
		final IllegalArgumentException ex = assertThrows(
				IllegalArgumentException.class,
				() -> UpdateAssignMergeUtil.toDecoded(plan, lits, schema));
		assertTrue(ex.getMessage().contains("same column"));
	}

	private static TableSchema schema() {
		return TableSchema.builder("m")
				.primaryKey("id", SqlType.INT)
				.column("n", SqlType.INT, true)
				.column("v", SqlType.VARCHAR, true)
				.build();
	}
}
