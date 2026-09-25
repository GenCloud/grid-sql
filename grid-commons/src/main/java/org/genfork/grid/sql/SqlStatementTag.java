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
package org.genfork.grid.sql;

/**
 * Wire / result statement tags (DDL ack and DML affected).
 *
 * Lives in {@code grid-commons} (shared client/server; JDK-only).
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public enum SqlStatementTag {
	AUTH("AUTH"),
	SESSION_OPEN("SESSION_OPEN"),
	SESSION_CLOSE("SESSION_CLOSE"),
	BEGIN("BEGIN"),
	COMMIT("COMMIT"),
	ROLLBACK("ROLLBACK"),
	SAVEPOINT("SAVEPOINT"),
	ROLLBACK_TO_SAVEPOINT("ROLLBACK TO SAVEPOINT"),
	RELEASE_SAVEPOINT("RELEASE SAVEPOINT"),
	PREPARE("PREPARE"),
	EXECUTE("EXECUTE"),
	DEALLOCATE("DEALLOCATE"),
	CREATE_TABLE("CREATE TABLE"),
	DROP_TABLE("DROP TABLE"),
	CREATE_INDEX("CREATE INDEX"),
	DROP_INDEX("DROP INDEX"),
	CREATE_SCHEMA("CREATE SCHEMA"),
	DROP_SCHEMA("DROP SCHEMA"),
	SET_SCHEMA("SET SCHEMA"),
	SET_REMOTE_DIRTY("SET REMOTE_DIRTY"),
	ALTER_TABLE("ALTER TABLE"),
	CREATE_VIEW("CREATE VIEW"),
	DROP_VIEW("DROP VIEW"),
	CREATE_MATERIALIZED_VIEW("CREATE MATERIALIZED VIEW"),
	REFRESH_MATERIALIZED_VIEW("REFRESH MATERIALIZED VIEW"),
	CREATE_FUNCTION("CREATE FUNCTION"),
	DROP_FUNCTION("DROP FUNCTION"),
	CREATE_TRIGGER("CREATE TRIGGER"),
	DROP_TRIGGER("DROP TRIGGER"),
	CREATE_SEQUENCE("CREATE SEQUENCE"),
	DROP_SEQUENCE("DROP SEQUENCE"),
	INSERT("INSERT"),
	UPDATE("UPDATE"),
	DELETE("DELETE"),
	TRUNCATE("TRUNCATE"),
	MERGE("MERGE"),
	ANALYZE("ANALYZE"),
	SELECT("SELECT"),
	EXPLAIN("EXPLAIN"),
	CREATE_USER("CREATE USER"),
	DROP_USER("DROP USER"),
	ALTER_USER("ALTER USER"),
	CREATE_ROLE("CREATE ROLE"),
	DROP_ROLE("DROP ROLE"),
	GRANT("GRANT"),
	REVOKE("REVOKE"),
	PIN("PIN"),
	UNPIN("UNPIN");

	private final String wire;

	SqlStatementTag(String wire) {
		this.wire = wire;
	}

	/** Stable wire / protocol label. */
	public String wire() {
		return wire;
	}

	public static SqlStatementTag fromWire(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new IllegalArgumentException("tag required");
		}
		for (SqlStatementTag t : values()) {
			if (t.wire.equalsIgnoreCase(raw) || t.name().equalsIgnoreCase(raw)) {
				return t;
			}
		}
		throw new IllegalArgumentException("Unknown SqlStatementTag: " + raw);
	}
}