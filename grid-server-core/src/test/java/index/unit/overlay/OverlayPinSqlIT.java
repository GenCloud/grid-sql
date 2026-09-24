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
package index.unit.overlay;

import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.context.config.GridConfigurationProperties;
import org.genfork.grid.overlay.OverlayStore;
import org.genfork.grid.replication.ReplicationCoordinator;
import org.genfork.grid.replication.metrics.ReplicationMetrics;
import org.genfork.grid.sql.SqlEngine;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.SqlStatementParser;
import org.genfork.grid.sql.ast.AdminAst.PinSql;
import org.genfork.grid.sql.ast.AdminAst.UnpinSql;
import org.genfork.grid.sql.SqlStatementTag;
import org.genfork.grid.store.TableStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL PIN / UNPIN → {@link OverlayStore}; swarm migrate gate via {@link ReplicationCoordinator}.
 *
 * @author: GenCloud
 * @date: 2026/02
 * @since: 1.0
 */
public class OverlayPinSqlIT {

	private SqlEngine engine;
	private OverlayStore overlay;

	@BeforeEach
	void setUp() {
		overlay = new OverlayStore(true);
		engine = new SqlEngine(new TableCatalog(), null, 4);
		engine.setOverlay(overlay, 0L);
		engine.execute("CREATE TABLE pin_t (id INT PRIMARY KEY, v INT)");
		engine.execute("INSERT INTO pin_t VALUES (1, 10)");
	}

	@Test
	void parsePinUnpin() {
		final PinSql pin = assertInstanceOf(PinSql.class,
				SqlStatementParser.parse("PIN KEY pin_t 1 TTL 500 QOS gold"));
		assertEquals("pin_t", pin.table());
		assertEquals(1, pin.keyLiteral());
		assertEquals(500L, pin.ttlMsOrNull());
		assertEquals("gold", pin.qosTagOrNull());

		final UnpinSql unpin = assertInstanceOf(UnpinSql.class,
				SqlStatementParser.parse("UNPIN KEY pin_t 1"));
		assertEquals("pin_t", unpin.table());
		assertEquals(1, unpin.keyLiteral());
	}

	@Test
	void pinBlocksOnlyPinnedShardUntilTtlExpires() throws Exception {
		final SqlResult pin = engine.execute("PIN KEY pin_t 1 TTL 80 QOS hot");
		assertEquals(SqlStatementTag.PIN, pin.statementTag());
		assertTrue(overlay.hasAnyPinned());
		final TableStore store = engine.catalog().getStore("pin_t");
		final byte[] keyBytes = store.keyBytesForPk(1);
		assertTrue(overlay.get("pin_t", keyBytes).isPresent());
		final int pinnedShard = store.shardOf(keyBytes);
		final int otherShard = (pinnedShard + 1) % 4;
		assertTrue(overlay.pinsDomainShard("pin_t", pinnedShard, 4));
		assertFalse(overlay.pinsDomainShard("pin_t", otherShard, 4),
				"unrelated shard must stay migratable");

		final ReplicationCoordinator coordinator = newCoordinatorWithOverlay(overlay);
		assertTrue(coordinator.isOverlayBlockingSwarmMigrate());
		assertTrue(coordinator.isOverlayBlockingShardMigrate("pin_t", pinnedShard),
				"pinned shard must block migrate");
		assertFalse(coordinator.isOverlayBlockingShardMigrate("pin_t", otherShard),
				"unrelated shard must stay migratable under per-shard PIN");
		assertTrue(overlay.pinsDomainShard("pin_t", pinnedShard, 4));

		Thread.sleep(120L);
		assertFalse(overlay.hasAnyPinned(), "TTL expire clears live pins");
		assertFalse(overlay.pinsDomainShard("pin_t", pinnedShard, 4));
	}

	@Test
	void unpinClearsPin() {
		engine.execute("PIN KEY pin_t 1 QOS sticky");
		assertTrue(overlay.hasAnyPinned());
		final SqlResult unpin = engine.execute("UNPIN KEY pin_t 1");
		assertEquals(SqlStatementTag.UNPIN, unpin.statementTag());
		assertFalse(overlay.hasAnyPinned());
	}

	@Test
	void autoPinOnHotWriteWhenConfigured() {
		final OverlayStore auto = new OverlayStore(true);
		engine.setOverlay(auto, 5_000L);
		final long before = ReplicationMetrics.overlayPinnedKeys();
		engine.execute("INSERT INTO pin_t VALUES (2, 20)");
		assertTrue(auto.hasAnyPinned());
		assertTrue(ReplicationMetrics.overlayPinnedKeys() >= before);
	}

	private static ReplicationCoordinator newCoordinatorWithOverlay(OverlayStore store) {
		final GridConfigurationProperties props = new GridConfigurationProperties();
		props.getReplication().setEnabled(false);
		props.getDurability().setEnabled(false);
		final ReplicationCoordinator c = new ReplicationCoordinator(props);
		c.setOverlayStore(store);
		return c;
	}
}
