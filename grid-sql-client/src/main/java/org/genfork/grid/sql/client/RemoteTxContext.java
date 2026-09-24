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

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Remote {@link TxContext}: SESSION_OPEN already done; owns BEGIN/COMMIT/ROLLBACK + CLOSE.
 * Carries opaque {@link #prepareHandle()} from BEGIN for concurrent mux identity.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public final class RemoteTxContext implements TxContext {
    private final RemoteConnection connection;
    private final int sessionId;
    private final long prepareHandle;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean completed = new AtomicBoolean();

    RemoteTxContext(RemoteConnection connection, int sessionId, long prepareHandle) {
        this.connection = connection;
        this.sessionId = sessionId;
        this.prepareHandle = prepareHandle;
    }

    @Override
    public long prepareHandle() {
        return prepareHandle;
    }

    @Override
    public Statement createStatement(String sql) {
        return new RemoteTxStatement(connection, sessionId, sql, closed);
    }

    @Override
    public Flux<Result> executeBatch(List<String> sqls) {
        if (closed.get()) {
            return Flux.error(new IllegalStateException("TxContext closed"));
        }
        return connection.execBatch(sessionId, sqls);
    }

	@Override
	public Mono<Void> commit() {
		return finish("COMMIT");
	}

	@Override
	public Mono<Void> rollback() {
		return finish("ROLLBACK");
	}

	@Override
	public Mono<Savepoint> savepoint(String name) {
		final String sql = SqlSavepointSql.savepoint(name);
		final Savepoint handle = new NamedSavepoint(SqlSavepointSql.requireIdent(name));
		return control(sql).thenReturn(handle);
	}

	@Override
	public Mono<Void> rollbackTo(Savepoint savepoint) {
		if (savepoint == null) {
			return Mono.error(new IllegalArgumentException("savepoint required"));
		}
		return control(SqlSavepointSql.rollbackTo(savepoint.name()));
	}

	@Override
	public Mono<Void> release(Savepoint savepoint) {
		if (savepoint == null) {
			return Mono.error(new IllegalArgumentException("savepoint required"));
		}
		return control(SqlSavepointSql.release(savepoint.name()));
	}

	@Override
	public Mono<Void> close() {
		if (completed.get()) {
			return Mono.empty();
		}

		return rollback().onErrorComplete();
	}

	/** Control SQL that must keep the TX open (unlike COMMIT/ROLLBACK). */
	private Mono<Void> control(String sql) {
		if (closed.get() || completed.get()) {
			return Mono.error(new IllegalStateException("TxContext closed"));
		}
		return connection.exec(sessionId, sql, null).then();
	}

	private Mono<Void> finish(String sql) {
		if (!completed.compareAndSet(false, true)) {
			return Mono.empty();
		}

		return connection.exec(sessionId, sql, null)
				.then(
						Mono.defer(() -> {
							closed.set(true);
							return connection.closeSession(sessionId);
						})
				)
				.onErrorResume(err -> {
					closed.set(true);
					return connection.closeSession(sessionId).then(Mono.error(err));
				});
	}

    private static final class RemoteTxStatement extends AbstractBoundStatement {
        private final RemoteConnection connection;
        private final int sessionId;
        private final AtomicBoolean closed;

        RemoteTxStatement(RemoteConnection connection, int sessionId, String sql, AtomicBoolean closed) {
            super(sql);
            this.connection = connection;
            this.sessionId = sessionId;
            this.closed = closed;
        }

        @Override
        public Flux<Result> execute() {
            if (closed.get()) {
                return Flux.error(new IllegalStateException("TxContext closed"));
            }

            return connection.exec(sessionId, sql, boundArgs(), fetchWindowOrZero());
        }
    }
}
