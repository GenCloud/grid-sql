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
package org.genfork.grid.query.plan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.annotations.VisibleForTesting;

import org.genfork.grid.query.filters.FilterCondition;
import org.genfork.grid.query.filters.impl.AndCondition;
import org.genfork.grid.query.filters.impl.CompositeIndexCondition;
import org.genfork.grid.query.filters.impl.LogicalOperatorCondition;

/**
 * Keeps non-prefix EQ predicates as wire residual on a composite left-prefix seek.
 * <p>
 * Candidate keys come from {@link CompositeIndexCondition} only; residual legs filter via
 * {@code matches()} (see {@code GridCompositeIndex#needsResidualMatch}).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
public final class CompositeIndexPrefixRewriteUtil {
	private CompositeIndexPrefixRewriteUtil() {
	}

	/**
	 * @param prefix           left-prefix composite seek
	 * @param fieldToFilterMap EQ leaves extracted from the AND tree
	 * @param indexFields      composite index column order
	 * @param prefixLen        contiguous EQ prefix length ({@code >= 1}, {@code < indexFields.size()})
	 * @param otherFilters     non-{@link LogicalOperatorCondition} siblings (may be empty)
	 * @return prefix alone, or {@code And(prefix, residual…)}
	 */
	public static FilterCondition withResidual(
			CompositeIndexCondition prefix,
			Map<String, LogicalOperatorCondition> fieldToFilterMap,
			List<String> indexFields,
			int prefixLen,
			List<FilterCondition> otherFilters
	) {
		final List<FilterCondition> parts = new ArrayList<>();
		parts.add(prefix);
		appendResidualEq(parts, fieldToFilterMap, indexFields, prefixLen);
		if (otherFilters != null && !otherFilters.isEmpty()) {
			parts.addAll(otherFilters);
		}
		if (parts.size() == 1) {
			return prefix;
		}
		return buildAndTree(parts, 0, parts.size() - 1);
	}

	@VisibleForTesting
	static void appendResidualEq(
			List<FilterCondition> out,
			Map<String, LogicalOperatorCondition> fieldToFilterMap,
			List<String> indexFields,
			int prefixLen
	) {
		if (fieldToFilterMap == null || fieldToFilterMap.isEmpty()) {
			return;
		}
		final Set<String> indexFieldSet = new HashSet<>(indexFields);
		for (int i = prefixLen; i < indexFields.size(); i++) {
			final LogicalOperatorCondition filter = fieldToFilterMap.get(indexFields.get(i));
			if (filter != null) {
				out.add(filter);
			}
		}
		for (Map.Entry<String, LogicalOperatorCondition> entry : fieldToFilterMap.entrySet()) {
			if (!indexFieldSet.contains(entry.getKey())) {
				out.add(entry.getValue());
			}
		}
	}

	@VisibleForTesting
	static FilterCondition buildAndTree(List<FilterCondition> filters, int start, int end) {
		if (start > end) {
			return null;
		}
		if (start == end) {
			return filters.get(start);
		}
		final int mid = (start + end) / 2;
		final FilterCondition left = buildAndTree(filters, start, mid);
		final FilterCondition right = buildAndTree(filters, mid + 1, end);
		return new AndCondition(left, right);
	}
}
