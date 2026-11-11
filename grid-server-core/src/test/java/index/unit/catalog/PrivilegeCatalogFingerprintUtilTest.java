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
import org.genfork.grid.catalog.PrivilegeCatalogFingerprintUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Privilege catalog fingerprint stability (wire snapshot bytes).
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
class PrivilegeCatalogFingerprintUtilTest {
	private static final String ADMIN_PASSWORD = "admin-secret";
	private static final String APP_PASSWORD = "app-secret";

	@Test
	void openCatalogReportsZero() {
		assertEquals(0L, PrivilegeCatalogFingerprintUtil.fingerprint(null));
		assertEquals(0L, PrivilegeCatalogFingerprintUtil.fingerprint(new PrivilegeCatalog()));
	}

	@Test
	void sameSnapshotBytesSameFingerprint() {
		final PrivilegeCatalog privileges = new PrivilegeCatalog();
		privileges.createUser("admin", ADMIN_PASSWORD);
		final byte[] snap = privileges.toSnapshotBytes();
		assertEquals(
				PrivilegeCatalogFingerprintUtil.fingerprintBytes(snap),
				PrivilegeCatalogFingerprintUtil.fingerprint(privileges));
		assertEquals(
				PrivilegeCatalogFingerprintUtil.fingerprintBytes(snap),
				PrivilegeCatalogFingerprintUtil.fingerprintBytes(snap));
	}

	@Test
	void differentUserSetsDiverge() {
		final PrivilegeCatalog a = new PrivilegeCatalog();
		a.createUser("admin", ADMIN_PASSWORD);
		final PrivilegeCatalog b = new PrivilegeCatalog();
		b.createUser("admin", ADMIN_PASSWORD);
		b.createUser("app", APP_PASSWORD);
		assertNotEquals(
				PrivilegeCatalogFingerprintUtil.fingerprint(a),
				PrivilegeCatalogFingerprintUtil.fingerprint(b));
		assertTrue(PrivilegeCatalogFingerprintUtil.fingerprint(a) != 0L);
		assertTrue(PrivilegeCatalogFingerprintUtil.fingerprint(b) != 0L);
	}

	@Test
	void fingerprintStableAcrossRepeatedEncodes() {
		final PrivilegeCatalog privileges = new PrivilegeCatalog();
		privileges.createUser("admin", ADMIN_PASSWORD);
		privileges.createUser("app", APP_PASSWORD);
		final long first = PrivilegeCatalogFingerprintUtil.fingerprint(privileges);
		for (int i = 0; i < 64; i++) {
			assertEquals(first, PrivilegeCatalogFingerprintUtil.fingerprint(privileges));
			assertArrayEquals(privileges.toSnapshotBytes(), privileges.toSnapshotBytes());
		}
	}
}
