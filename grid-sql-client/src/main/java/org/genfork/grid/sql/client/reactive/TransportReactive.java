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
package org.genfork.grid.sql.client.reactive;

import reactor.core.publisher.Flux;

import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlStatementTag;
import org.genfork.grid.sql.client.DefaultRowMetadata;
import org.genfork.grid.sql.client.EngineResult;
import org.genfork.grid.sql.client.Result;
import org.genfork.grid.sql.client.StreamingResult;
import org.genfork.grid.sql.client.transport.RowPortal;
import org.genfork.grid.sql.client.transport.TransportOutcome;

/**
 * Maps transport {@link TransportOutcome} to SPI {@link Result} (row Flux from portal demand).
 * <p>
 * No {@link java.util.concurrent.CompletableFuture} bridge — reactive exchanges use
 * {@link ReactiveExecExchange} / {@link ReactiveBatchExchange} Sinks directly.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public final class TransportReactive {
	private static final String CANCEL_TAG = "CANCEL";

	private TransportReactive() {
	}

	public static Result toResult(TransportOutcome outcome) {
		return switch (outcome) {
			case TransportOutcome.Auth ignored ->
					new EngineResult(SqlResult.ddl(SqlStatementTag.AUTH));
			case TransportOutcome.SessionOpen open ->
					new EngineResult(SqlResult.affected(SqlStatementTag.SESSION_OPEN, open.sessionId()));
			case TransportOutcome.SessionClose ignored ->
					new EngineResult(SqlResult.ddl(SqlStatementTag.SESSION_CLOSE));
			case TransportOutcome.Dml dml -> {
				if (CANCEL_TAG.equalsIgnoreCase(dml.tag())) {
					yield new EngineResult(SqlResult.affected(SqlStatementTag.SELECT, 0L));
				}
				yield new EngineResult(SqlResult.affected(SqlStatementTag.fromWire(dml.tag()), dml.affected()));
			}
			case TransportOutcome.ResultSet rs -> new StreamingResult(
					new DefaultRowMetadata(rs.columns()),
					rowsFlux(rs.portal()),
					rs.portal()::cancel);
		};
	}

	private static Flux<Object[]> rowsFlux(RowPortal portal) {
		return Flux.create(sink -> {
			portal.attach(new RowPortal.RowListener() {
				@Override
				public void onRow(Object[] row) {
					sink.next(row);
				}

				@Override
				public void onComplete() {
					sink.complete();
				}

				@Override
				public void onError(Throwable error) {
					sink.error(error);
				}
			});
			sink.onRequest(n -> {
				if (n > 0L) {
					portal.request(n);
				}
			});
			sink.onCancel(portal::cancel);
		});
	}
}
