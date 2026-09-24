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
package org.genfork.grid.sql.client;

import org.genfork.grid.sql.SqlResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.BiFunction;

/**
 * Adapts {@link SqlResult} to client {@link Result}.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public final class EngineResult implements Result {
	private final SqlResult sqlResult;

	public EngineResult(SqlResult sqlResult) {
		this.sqlResult = sqlResult;
	}

	@Override
	public Mono<Long> getRowsUpdated() {
		if (sqlResult.kind() == SqlResult.Kind.RESULT_SET) {
			return Mono.just(0L);
		}
		return Mono.just(sqlResult.rowsAffected());
	}

	@Override
	public boolean isResultSet() {
		return sqlResult.kind() == SqlResult.Kind.RESULT_SET;
	}

	@Override
	public RowMetadata rowMetadata() {
		if (!isResultSet()) {
			return null;
		}
		return new DefaultRowMetadata(sqlResult.columns());
	}

	@Override
	public <T> Flux<T> map(BiFunction<Row, RowMetadata, T> mappingFunction) {
		if (sqlResult.kind() != SqlResult.Kind.RESULT_SET) {
			return Flux.empty();
		}
		final DefaultRowMetadata meta = new DefaultRowMetadata(sqlResult.columns());
		return Flux.fromIterable(sqlResult.rows())
				.map(cells -> mappingFunction.apply(new ArrayRow(cells, meta), meta));
	}
}