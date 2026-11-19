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
package org.genfork.grid.catalog;

/**
 * Stable privilege-catalog fingerprint for HELLO / privilege-meta reconcile.
 * <p>
 * Hashes {@link PrivilegeCatalog#toSnapshotBytes()} (wire bytes only). Open/empty catalogs
 * report {@code 0} so legacy/pre-bind peers do not false-mismatch. Orthogonal to
 * {@link CatalogFingerprintUtil} schema object-set fingerprint.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
public final class PrivilegeCatalogFingerprintUtil {
	private static final long FNV_OFFSET = 0xcbf29ce484222325L;
	private static final long FNV_PRIME = 0x100000001b3L;

	private PrivilegeCatalogFingerprintUtil() {
	}

	/**
	 * FNV-1a 64-bit fingerprint of privilege snapshot bytes ({@code 0} when null/open).
	 */
	public static long fingerprint(PrivilegeCatalog privileges) {
		if (privileges == null || privileges.isOpen()) {
			return 0L;
		}
		return fingerprintBytes(privileges.toSnapshotBytes());
	}

	/**
	 * FNV-1a 64-bit fingerprint of raw privilege snapshot bytes ({@code 0} when null/empty).
	 */
	public static long fingerprintBytes(byte[] snapshotBytes) {
		if (snapshotBytes == null || snapshotBytes.length == 0) {
			return 0L;
		}
		long hash = FNV_OFFSET;
		for (int i = 0; i < snapshotBytes.length; i++) {
			hash ^= (snapshotBytes[i] & 0xffL);
			hash *= FNV_PRIME;
		}
		return hash;
	}
}
