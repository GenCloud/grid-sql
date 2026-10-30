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

import org.genfork.grid.catalog.PrivilegeCatalog;
import org.genfork.grid.catalog.SqlPrivilege;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repro: identical GRANT after load must not rewrite privileges.meta (hydrate storm).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
@DisplayName("PrivilegeCatalogPersistIfChanged")
class PrivilegeCatalogPersistIfChangedTest {
	private static final String ADMIN = "admin";
	private static final String APP = "app";
	private static final String SCHEMA = "auth_server";
	private static final String PASSWORD = "secret";
	private static final int HYDRATE_GRANT_REPLAYS = 32;

	@TempDir
	Path catalogDir;

	@Test
	@DisplayName("identical_grant_after_load_does_not_persist_again")
	void identicalGrantAfterLoadDoesNotPersistAgain() throws Exception {
		final PrivilegeCatalog seed = new PrivilegeCatalog(catalogDir);
		seed.createUser(ADMIN, PASSWORD);
		seed.createUser(APP, PASSWORD);
		seed.grant(APP, SCHEMA, "*", EnumSet.of(
				SqlPrivilege.DDL,
				SqlPrivilege.SELECT,
				SqlPrivilege.INSERT,
				SqlPrivilege.UPDATE,
				SqlPrivilege.DELETE));
		final Path meta = catalogDir.resolve(PrivilegeCatalog.META_FILE_NAME);
		assertTrue(Files.isRegularFile(meta));

		final PrivilegeCatalog loaded = new PrivilegeCatalog(catalogDir);
		assertTrue(loaded.containsAllGrants(APP, SCHEMA, "*", EnumSet.of(
				SqlPrivilege.DDL,
				SqlPrivilege.SELECT,
				SqlPrivilege.INSERT,
				SqlPrivilege.UPDATE,
				SqlPrivilege.DELETE)));

		final FileTime afterLoad = Files.getLastModifiedTime(meta);
		for (int i = 0; i < HYDRATE_GRANT_REPLAYS; i++) {
			loaded.grant(APP, SCHEMA, "*", EnumSet.of(
					SqlPrivilege.DDL,
					SqlPrivilege.SELECT,
					SqlPrivilege.INSERT,
					SqlPrivilege.UPDATE,
					SqlPrivilege.DELETE));
		}
		assertEquals(afterLoad, Files.getLastModifiedTime(meta),
				"identical GRANT hydrate must not rewrite privileges.meta");
	}

	@Test
	@DisplayName("new_privilege_persists_once")
	void newPrivilegePersistsOnce() throws Exception {
		final PrivilegeCatalog cat = new PrivilegeCatalog(catalogDir);
		cat.createUser(ADMIN, PASSWORD);
		cat.createUser(APP, PASSWORD);
		final Path meta = catalogDir.resolve(PrivilegeCatalog.META_FILE_NAME);
		assertTrue(Files.isRegularFile(meta));
		final FileTime before = Files.getLastModifiedTime(meta);

		cat.grant(APP, SCHEMA, "*", EnumSet.of(SqlPrivilege.SELECT));
		final FileTime afterSelect = Files.getLastModifiedTime(meta);
		assertTrue(afterSelect.compareTo(before) >= 0);

		cat.grant(APP, SCHEMA, "*", EnumSet.of(SqlPrivilege.SELECT));
		assertEquals(afterSelect, Files.getLastModifiedTime(meta));

		cat.grant(APP, SCHEMA, "*", EnumSet.of(SqlPrivilege.INSERT));
		assertTrue(Files.getLastModifiedTime(meta).compareTo(afterSelect) >= 0);
	}
}
