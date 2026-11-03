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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Stable catalog object-set fingerprint for HELLO / replica-read admission.
 * <p>
 * Hash covers schemas, tables, views, free sequences, triggers, and functions (sorted keys).
 * Owned IDENTITY sequences are omitted — they are implied by table IDENTITY columns and are
 * not part of {@link CatalogDdlCompactor} / journal snapshot reconcile.
 * Mid-pipeline Object decode forbidden — keys are catalog strings only.
 *
 * @author: GenCloud
 * @date: 2026/11
 * @since: 1.3
 */
public final class CatalogFingerprintUtil {
	private static final long FNV_OFFSET = 0xcbf29ce484222325L;
	private static final long FNV_PRIME = 0x100000001b3L;
	private static final String KIND_SCHEMA = "S:";
	private static final String KIND_TABLE = "T:";
	private static final String KIND_VIEW = "V:";
	private static final String KIND_SEQ = "Q:";
	private static final String KIND_TRIGGER = "G:";
	private static final String KIND_FUNCTION = "F:";
	private static final char SEP = '\n';

	private CatalogFingerprintUtil() {
	}

	/**
	 * FNV-1a 64-bit fingerprint of the live catalog object-set (0 when catalog null/empty kinds).
	 */
	public static long fingerprint(TableCatalog catalog) {
		if (catalog == null) {
			return 0L;
		}
		final ArrayList<String> parts = new ArrayList<>();
		appendSorted(parts, KIND_SCHEMA, catalog.schemaNames());
		appendSorted(parts, KIND_TABLE, catalog.tableNames());
		appendSorted(parts, KIND_VIEW, catalog.viewNames());
		for (SequenceDef seq : catalog.sequenceDefs()) {
			if (seq == null || seq.name() == null || seq.name().isBlank()) {
				continue;
			}
			if (seq.ownedByTableOrNull() != null && !seq.ownedByTableOrNull().isBlank()) {
				continue;
			}
			parts.add(KIND_SEQ + seq.name().toLowerCase(Locale.ROOT));
		}
		for (String table : catalog.tableNames()) {
			for (TriggerDef trigger : catalog.triggersForTable(table)) {
				if (trigger != null && trigger.name() != null && !trigger.name().isBlank()) {
					parts.add(KIND_TRIGGER + trigger.name().toLowerCase(Locale.ROOT));
				}
			}
		}
		for (String name : catalog.functionNames()) {
			parts.add(KIND_FUNCTION + name.toLowerCase(Locale.ROOT));
		}
		parts.sort(Comparator.naturalOrder());
		long hash = FNV_OFFSET;
		for (String part : parts) {
			final byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
			for (byte b : bytes) {
				hash ^= (b & 0xffL);
				hash *= FNV_PRIME;
			}
			hash ^= SEP;
			hash *= FNV_PRIME;
		}
		return hash;
	}

	private static void appendSorted(List<String> parts, String kindPrefix, Iterable<String> names) {
		final ArrayList<String> sorted = new ArrayList<>();
		for (String name : names) {
			if (name != null && !name.isBlank()) {
				sorted.add(name.toLowerCase(Locale.ROOT));
			}
		}
		sorted.sort(Comparator.naturalOrder());
		for (String name : sorted) {
			parts.add(kindPrefix + name);
		}
	}
}
