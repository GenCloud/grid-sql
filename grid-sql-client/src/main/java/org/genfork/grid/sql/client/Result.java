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

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.BiFunction;

/**
 * Outcome of a statement execution (rows or update count).
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public interface Result {
	Mono<Long> getRowsUpdated();

	<T> Flux<T> map(BiFunction<Row, RowMetadata, T> mappingFunction);

	/**
	 * {@code true} when this outcome is a result set (possibly empty), not DDL/DML count.
	 */
	default boolean isResultSet() {
		return false;
	}

	/**
	 * Column metadata when {@link #isResultSet()} is true; otherwise {@code null}.
	 */
	default RowMetadata rowMetadata() {
		return null;
	}

	/**
	 * Best-effort cancel of an in-flight result stream (wire {@code CANCEL} when remote).
	 * No-op when not streaming / already complete.
	 */
	default void cancel() {
	}
}
