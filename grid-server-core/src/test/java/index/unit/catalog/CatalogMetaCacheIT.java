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
package index.unit.catalog;

import org.genfork.grid.catalog.CatalogMetaCache;
import org.genfork.grid.catalog.ColumnDef;
import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.TableCatalog;
import org.genfork.grid.catalog.TableSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CatalogMetaCache LRU eviction, DDL invalidate, disk miss, information_schema path.
 *
 * @author: GenCloud
 * @date: 2025/07
 * @since: 1.0
 */
@DisplayName("CatalogMetaCacheIT")
class CatalogMetaCacheIT {
	private static final int CACHE_SIZE = 2;

	@TempDir
	Path tmp;

	@Test
	void lruEvictsColdEntries() {
		final CatalogMetaCache cache = new CatalogMetaCache(CACHE_SIZE);
		cache.put("t1", schema("t1", 1L));
		cache.put("t2", schema("t2", 1L));
		assertEquals(CACHE_SIZE, cache.size());
		assertNotNull(cache.get("t1"));
		cache.put("t3", schema("t3", 1L));
		assertEquals(CACHE_SIZE, cache.size());
		assertNull(cache.get("t2"));
		assertNotNull(cache.get("t1"));
		assertNotNull(cache.get("t3"));
	}

	@Test
	void ddlInvalidate_clearsEntry() {
		final TableCatalog catalog = new TableCatalog(tmp, CACHE_SIZE);
		catalog.createTable(schema("orders", 1L));
		assertNotNull(catalog.metaCache().get("orders"));
		catalog.dropTable("orders");
		assertNull(catalog.metaCache().get("orders"));
	}

	@Test
	void getSchema_warmsCacheFromRegistry() {
		final TableCatalog catalog = new TableCatalog(tmp, CACHE_SIZE);
		catalog.createTable(schema("a", 1L));
		catalog.metaCache().invalidateAll();
		assertEquals(0, catalog.metaCache().size());
		assertNotNull(catalog.getSchema("a"));
		assertTrue(catalog.metaCache().size() >= 1);
	}

	@Test
	void alterRefreshesFingerprint() {
		final TableCatalog catalog = new TableCatalog(tmp, 8);
		final TableSchema base = schema("alt", 1L);
		catalog.createTable(base);
		final long crcBefore = catalog.metaCache().get("alt").crc();
		final List<ColumnDef> cols = new ArrayList<>(base.columns());
		cols.add(new ColumnDef("extra", SqlType.INT, true, cols.size(), false, false));
		final TableSchema altered = new TableSchema("alt", cols, List.of(), List.of(), 2L);
		catalog.replaceSchema(altered);
		final CatalogMetaCache.CachedMeta cached = catalog.metaCache().get("alt");
		assertNotNull(cached);
		assertEquals(2L, cached.schema().schemaEpoch());
		assertEquals(CatalogMetaCache.fingerprint(altered), cached.crc());
		assertTrue(cached.crc() != crcBefore);
	}

	@Test
	void schemasSnapshot_usesMetaCache() {
		final TableCatalog catalog = new TableCatalog(tmp, 8);
		catalog.createTable(schema("info_t", 1L));
		catalog.metaCache().invalidateAll();
		assertEquals(0, catalog.metaCache().size());
		assertEquals(1, catalog.schemas().size());
		assertTrue(catalog.metaCache().contains("info_t") || catalog.metaCache().size() >= 1);
	}

	@Test
	void diskMissLoadsMetaViaGridFs() {
		final TableCatalog writer = new TableCatalog(tmp, 8);
		writer.createTable(schema("disk_t", 3L));
		final TableCatalog reader = new TableCatalog(tmp, 8);
		assertFalse(reader.exists("disk_t"));
		final TableSchema loaded = reader.getSchema("disk_t");
		assertNotNull(loaded);
		assertEquals(3L, loaded.schemaEpoch());
		assertEquals(CatalogMetaCache.fingerprint(loaded), reader.metaCache().get("disk_t").crc());
	}

	private static TableSchema schema(String name, long epoch) {
		return new TableSchema(
				name,
				List.of(new ColumnDef("id", SqlType.BIGINT, false, 0, true, false, false, null)),
				List.of(),
				List.of(),
				epoch
		);
	}
}