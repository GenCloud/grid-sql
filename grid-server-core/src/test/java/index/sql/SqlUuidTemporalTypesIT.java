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
package index.sql;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlSession;
import org.genfork.grid.sql.SqlResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * P2.1 UUID + P2.2 DATE/TIME/TIMESTAMPTZ DDL INSERT/SELECT round-trip.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
class SqlUuidTemporalTypesIT {
	private SqlEngine engine;

	@BeforeEach
	void setUp() {
		engine = new SqlEngine(new TableCatalog(), null, 4);
		engine.setDefaultTimezone(ZoneOffset.UTC);
	}

	@Test
	void uuidCreateInsertSelect() {
		engine.execute("CREATE TABLE docs (id UUID PRIMARY KEY, title VARCHAR)");
		engine.execute(
				"INSERT INTO docs VALUES (UUID '550e8400-e29b-41d4-a716-446655440000', 'hello')");
		final SqlResult r = engine.execute("SELECT id, title FROM docs");
		assertEquals(1, r.rows().size());
		assertEquals(
				UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
				r.rows().getFirst()[0]);
		assertEquals("hello", r.rows().getFirst()[1]);
	}

	@Test
	void uuidCastLiteral() {
		engine.execute("CREATE TABLE docs (id INT PRIMARY KEY, u UUID)");
		engine.execute(
				"INSERT INTO docs VALUES (1, CAST('550e8400-e29b-41d4-a716-446655440000' AS UUID))");
		final SqlResult r = engine.execute("SELECT u FROM docs WHERE id = 1");
		assertInstanceOf(UUID.class, r.rows().getFirst()[0]);
	}

	@Test
	void dateTimeTimestamptzRoundTrip() {
		engine.execute(
				"CREATE TABLE ev (id INT PRIMARY KEY, d DATE, t TIME, ts TIMESTAMP, tsz TIMESTAMPTZ)");
		engine.execute(
				"INSERT INTO ev VALUES (1, DATE '2026-09-17', TIME '13:45:30', "
						+ "TIMESTAMP '2026-09-17T13:45:30', TIMESTAMPTZ '2026-09-17T10:45:30Z')");
		final SqlResult r = engine.execute("SELECT d, t, ts, tsz FROM ev WHERE id = 1");
		assertEquals(1, r.rows().size());
		assertEquals(LocalDate.of(2026, 9, 17), r.rows().getFirst()[0]);
		assertEquals(LocalTime.of(13, 45, 30), r.rows().getFirst()[1]);
		assertEquals("2026-09-17T13:45:30", r.rows().getFirst()[2]);
		assertEquals(Instant.parse("2026-09-17T10:45:30Z"), r.rows().getFirst()[3]);
	}

	@Test
	void timestamptzZoneLessUsesSessionTimezone() {
		final SqlSession session = engine.newSession();
		session.setTimezone(ZoneOffset.ofHours(3));
		engine.execute(session,
				"CREATE TABLE ev (id INT PRIMARY KEY, tsz TIMESTAMPTZ)");
		engine.execute(session,
				"INSERT INTO ev VALUES (1, TIMESTAMPTZ '2026-09-17T12:00:00')");
		final SqlResult r = engine.execute(session, "SELECT tsz FROM ev");
		assertEquals(Instant.parse("2026-09-17T09:00:00Z"), r.rows().getFirst()[0]);
	}
}