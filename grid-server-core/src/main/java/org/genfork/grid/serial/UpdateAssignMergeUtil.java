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
package org.genfork.grid.serial;

import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.query.plan.UpdatePlan;
import org.genfork.grid.replication.codec.ModifyPayload;
import org.genfork.grid.replication.codec.ModifyPayload.Decoded;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Build a single {@link Decoded} assign list from RMW {@link UpdatePlan} plus optional SET literals.
 * <p>
 * One {@link BlobFieldModifier#apply} pass — never RMW then literal as two UPSERTs.
 *
 * @author: GenCloud
 * @date: 2025/06
 * @since: 1.0
 */
public final class UpdateAssignMergeUtil {
	private static final String ERR_EMPTY = "UPDATE assigns empty";
	private static final String ERR_DUP_FIELD = "UPDATE SET assigns the same column more than once: ";
	private static final String ERR_PLAN_NULL = "UpdatePlan required";

	private UpdateAssignMergeUtil() {
	}

	/**
	 * Merge plan RMW assigns with literal SETs into one Decoded list (schema-validated).
	 *
	 * @param plan     non-null RMW plan
	 * @param literals optional literal SET map (may be null/empty)
	 * @param schema   target table schema
	 */
	public static List<Decoded> toDecoded(
			UpdatePlan plan,
			Map<String, Object> literals,
			TableSchema schema
	) {
		if (plan == null) {
			throw new IllegalArgumentException(ERR_PLAN_NULL);
		}
		final int litSize = literals == null ? 0 : literals.size();
		final int capacity = plan.assigns().size() + litSize;
		if (capacity == 0) {
			throw new IllegalArgumentException(ERR_EMPTY);
		}
		final Set<String> seen = new HashSet<>(capacity * 2);
		final List<Decoded> out = new ArrayList<>(capacity);
		for (UpdatePlan.FieldAssign a : plan.assigns()) {
			final String field = a.fieldName();
			schema.requireColumn(field);
			remember(seen, field);
			out.add(new Decoded(a.modifyKind(), field, a.modifyArg()));
		}
		if (literals != null) {
			for (Map.Entry<String, Object> e : literals.entrySet()) {
				final String field = e.getKey();
				schema.requireColumn(field);
				remember(seen, field);
				out.add(new Decoded(
						ModifyPayload.KIND_LITERAL_SET,
						field,
						ModifyLiteralArgUtil.encode(e.getValue())));
			}
		}
		return out;
	}

	private static void remember(Set<String> seen, String field) {
		final String key = field.toLowerCase(Locale.ROOT);
		if (!seen.add(key)) {
			throw new IllegalArgumentException(ERR_DUP_FIELD + field);
		}
	}
}
