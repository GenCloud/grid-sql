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
package org.genfork.grid.sql.util;

import org.genfork.grid.catalog.TableCatalog;

/**
 * Catalog DDL epoch allocation: OpLog-stamped remote epoch under replicated apply,
 * otherwise local {@link TableCatalog#nextEpoch()} above {@code maxApplied}.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
public final class CatalogDdlEpochUtil {
	private static final ThreadLocal<Long> REPLICATED_SCHEMA_EPOCH = new ThreadLocal<>();

	private CatalogDdlEpochUtil() {
	}

	/**
	 * Bind the OpLog DDL {@code schemaEpoch} for the duration of {@code applyReplicatedDdl} execute.
	 */
	public static void enterReplicated(long schemaEpoch) {
		if (schemaEpoch > 0L) {
			REPLICATED_SCHEMA_EPOCH.set(Long.valueOf(schemaEpoch));
		} else {
			REPLICATED_SCHEMA_EPOCH.remove();
		}
	}

	/** Clear the replicated-apply epoch binding. */
	public static void exitReplicated() {
		REPLICATED_SCHEMA_EPOCH.remove();
	}

	/**
	 * Allocate the schema epoch for a DDL mutate.
	 * <p>
	 * Under replicated apply with a bound remote stamp — return that stamp (never mint).
	 * Journal recover (apply flag without remote) and local propose — mint via
	 * {@link TableCatalog#nextEpoch()} after raising the floor to {@code maxApplied}.
	 */
	public static long allocate(TableCatalog catalog, boolean applyingReplicatedDdl) {
		if (catalog == null) {
			throw new IllegalArgumentException("catalog");
		}
		if (applyingReplicatedDdl) {
			final Long remote = REPLICATED_SCHEMA_EPOCH.get();
			if (remote != null && remote.longValue() > 0L) {
				return remote.longValue();
			}
		}
		catalog.ensureEpochAtLeast(catalog.maxAppliedDdlEpoch());
		return catalog.nextEpoch();
	}
}
