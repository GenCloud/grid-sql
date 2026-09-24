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

import java.util.List;

/**
 * Parsed SELECT plan fragment (filter / paging / sort / aggregate / joins).
 *
 * @author: GenCloud
 * @date: 2025/09
 * @since: 1.0
 */
public record QueryData(
		String table,
		String[] fields,
		FilterConditionData filter,
		PagingData paging,
		SortOrderData[] sortOrder,
		/** Null when not an aggregate query. */
		AggregateSpec aggregate,
		/** Empty when not a JOIN. */
		List<JoinSpec> joins
) {
	public QueryData(String table, String[] fields, FilterConditionData filter,
	                 PagingData paging, SortOrderData[] sortOrder) {
		this(table, fields, filter, paging, sortOrder, null, List.of());
	}

	public QueryData(String table, String[] fields, FilterConditionData filter,
	                 PagingData paging, SortOrderData[] sortOrder, AggregateSpec aggregate) {
		this(table, fields, filter, paging, sortOrder, aggregate, List.of());
	}

	public boolean isAggregate() {
		return aggregate != null && aggregate.isAggregate();
	}

	public boolean isJoin() {
		return joins != null && !joins.isEmpty();
	}
}
