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

/**
 * Executable SQL statement.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public interface Statement {
	Statement bind(int index, Object value);

	Statement bind(String name, Object value);

	/**
	 * Override FETCH portal window for this statement (≥1). Default: connection / URL option.
	 */
	default Statement fetchWindow(int rows) {
		return this;
	}

	Flux<Result> execute();

	/**
	 * Autocommit / TX convenience: first result rows-affected (0 for result sets).
	 */
	default Mono<Long> executeUpdate() {
		return execute().flatMap(Result::getRowsUpdated).next();
	}

	/**
	 * First mapped row of the first result set (empty Mono when none).
	 */
	default Mono<Row> fetchOne() {
		return execute()
				.filter(Result::isResultSet)
				.next()
				.flatMap(r -> r.map((row, meta) -> row).next());
	}
}