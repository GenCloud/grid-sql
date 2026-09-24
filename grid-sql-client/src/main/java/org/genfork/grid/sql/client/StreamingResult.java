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
 * Streaming {@link Result}: rows delivered as wire ROW_DATA arrives.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public final class StreamingResult implements Result {
	private final DefaultRowMetadata meta;
	private final Flux<Object[]> rows;
	private final Runnable cancelSender;

	public StreamingResult(DefaultRowMetadata meta, Flux<Object[]> rows, Runnable cancelSender) {
		this.meta = meta;
		this.rows = rows;
		this.cancelSender = cancelSender;
	}

	@Override
	public Mono<Long> getRowsUpdated() {
		return Mono.just(0L);
	}

	@Override
	public boolean isResultSet() {
		return true;
	}

	@Override
	public RowMetadata rowMetadata() {
		return meta;
	}

	@Override
	public void cancel() {
		if (cancelSender != null) {
			cancelSender.run();
		}
	}

	@Override
	public <T> Flux<T> map(BiFunction<Row, RowMetadata, T> mappingFunction) {
		return rows.map(cells -> mappingFunction.apply(new ArrayRow(cells, meta), meta));
	}
}
