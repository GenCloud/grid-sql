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

import java.util.Set;

import org.genfork.grid.catalog.SqlPrivilege;

/**
 * PIN / UNPIN / user / privilege AST types.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class AdminAst {
	private AdminAst() {
	}

	/**
	 * {@code PIN KEY table key [TTL ms] [QOS tag]} — soft overlay pin (not a row mutation).
	 *
	 * @param ttlMsOrNull null when omitted (permanent until UNPIN)
	 * @param qosTagOrNull null when omitted
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record PinSql(String table, Object keyLiteral, Long ttlMsOrNull, String qosTagOrNull) implements Stmt {
	}

	/**
	 * {@code UNPIN KEY table key}.
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record UnpinSql(String table, Object keyLiteral) implements Stmt {
	}

	/**
	 * {@code CREATE USER …}.
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record CreateUserSql(String user, String password) implements Stmt {
	}

	/**
	 * {@code DROP USER …}.
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record DropUserSql(String user) implements Stmt {
	}

	/**
	 * {@code ALTER USER … PASSWORD …}.
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record AlterUserPasswordSql(String user, String password) implements Stmt {
	}

	/**
	 * {@code GRANT … TO user}.
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record GrantSql(String user, boolean schemaScope, String target,
	                       Set<SqlPrivilege> privileges) implements Stmt {
	}

	/**
	 * {@code GRANT … TO ROLE role}.
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record GrantToRoleSql(String role, boolean schemaScope, String target,
	                             Set<SqlPrivilege> privileges) implements Stmt {
	}

	/**
	 * {@code GRANT ROLE role TO user}.
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record GrantRoleMembershipSql(String role, String user) implements Stmt {
	}

	/**
	 * {@code CREATE ROLE …}.
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record CreateRoleSql(String role) implements Stmt {
	}

	/**
	 * {@code DROP ROLE …}.
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record DropRoleSql(String role) implements Stmt {
	}

	/**
	 * {@code REVOKE …}.
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record RevokeSql(String user, boolean schemaScope, String target,
	                        Set<SqlPrivilege> privileges) implements Stmt {
	}
}