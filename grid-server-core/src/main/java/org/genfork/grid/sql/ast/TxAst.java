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
package org.genfork.grid.sql.ast;

/**
 * Transaction and prepared-statement AST types.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class TxAst {
	private TxAst() {
	}

	/**
	 * {@code BEGIN}.
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record BeginSql() implements Stmt {
	}

	/**
	 * {@code COMMIT}.
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record CommitSql() implements Stmt {
	}

	/**
	 * {@code ROLLBACK}.
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record RollbackSql() implements Stmt {
	}

	/**
	 * {@code SAVEPOINT name}.
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record SavepointSql(String name) implements Stmt {
	}

	/**
	 * {@code ROLLBACK TO [SAVEPOINT] name}.
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record RollbackToSavepointSql(String name) implements Stmt {
	}

	/**
	 * {@code RELEASE SAVEPOINT name}.
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record ReleaseSavepointSql(String name) implements Stmt {
	}

	/**
	 * {@code PREPARE name AS body}.
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record PrepareSql(String name, String bodySql) implements Stmt {
	}

	/**
	 * {@code EXECUTE name [USING …]}.
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record ExecuteSql(String name, Object[] binds) implements Stmt {
	}

	/**
	 * {@code DEALLOCATE name}.
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record DeallocateSql(String name) implements Stmt {
	}
}