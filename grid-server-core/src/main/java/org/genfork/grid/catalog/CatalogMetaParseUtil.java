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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import org.genfork.grid.fs.GridFs;
import org.genfork.grid.mem.index.IndexType;

/**
 * Reconstruct {@link TableSchema} from durable {@code *.meta} text (catalog recover / miss).
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
public final class CatalogMetaParseUtil {
	private static final String PREFIX_SCHEMA = "schema=";
	private static final String PREFIX_TABLE = "table=";
	private static final String PREFIX_EPOCH = "epoch=";
	private static final String PREFIX_COL = "col=";
	private static final String PREFIX_IDX = "idx=";
	private static final String PREFIX_FK = "fk=";
	private static final String PREFIX_CHECK = "check=";
	private static final int TABLE_PREFIX_LEN = PREFIX_TABLE.length();
	private static final int EPOCH_PREFIX_LEN = PREFIX_EPOCH.length();
	private static final int COL_PREFIX_LEN = PREFIX_COL.length();
	private static final int IDX_PREFIX_LEN = PREFIX_IDX.length();
	private static final int FK_PREFIX_LEN = PREFIX_FK.length();
	private static final int LEGACY_FK_MIN_PARTS = 7;
	private static final int FK_PARTS_WITH_SCHEMA = 8;
	private static final long DEFAULT_EPOCH = 1L;

	private CatalogMetaParseUtil() {
	}

	/** Reconstruct schema from persisted meta file (used by recovery). */
	public static TableSchema parseMeta(Path metaFile) throws IOException {
		final byte[] bytes = GridFs.readAll(metaFile);
		return parseMetaBytes(bytes, metaFile.toString());
	}

	/**
	 * Reconstruct schema from raw {@code *.meta} bytes (catalog miss path / GridFs).
	 */
	public static TableSchema parseMetaBytes(byte[] metaBytes, String sourceLabel) {
		Objects.requireNonNull(metaBytes, "metaBytes");
		final String body = new String(metaBytes, StandardCharsets.UTF_8);
		final String[] lines = body.split("\\R", -1);
		String table = null;
		String schemaPart = null;
		long epoch = DEFAULT_EPOCH;
		final List<ColumnDef> cols = new ArrayList<>();
		final List<IndexDef> idxs = new ArrayList<>();
		final List<FkDef> fks = new ArrayList<>();
		final List<CheckDef> checks = new ArrayList<>();
		for (String line : lines) {
			if (line.isEmpty()) {
				continue;
			}
			if (line.startsWith(PREFIX_SCHEMA)) {
				schemaPart = line.substring(PREFIX_SCHEMA.length()).trim();
			} else if (line.startsWith(PREFIX_TABLE)) {
				table = line.substring(TABLE_PREFIX_LEN);
			} else if (line.startsWith(PREFIX_EPOCH)) {
				epoch = Long.parseLong(line.substring(EPOCH_PREFIX_LEN));
			} else if (line.startsWith(PREFIX_COL)) {
				final String[] p = line.substring(COL_PREFIX_LEN).split(",", -1);
				final boolean identity = p.length > 5 && Boolean.parseBoolean(p[5]);
				final String seq = p.length > 6 && !p[6].isEmpty() ? p[6] : null;
				final String defaultExpr = p.length > 7 && !p[7].isEmpty()
						? new String(Base64.getUrlDecoder().decode(p[7]), StandardCharsets.UTF_8)
						: null;
				cols.add(new ColumnDef(
						p[0],
						SqlType.valueOf(p[1]),
						Boolean.parseBoolean(p[2]),
						cols.size(),
						Boolean.parseBoolean(p[3]),
						Boolean.parseBoolean(p[4]),
						identity,
						seq,
						defaultExpr
				));
			} else if (line.startsWith(PREFIX_IDX)) {
				final String[] p = line.substring(IDX_PREFIX_LEN).split(",", 3);
				idxs.add(new IndexDef(
						CatalogPersistUtil.sanitizeIndexName(p[0]),
						List.of(p[2].split("\\+")),
						IndexType.valueOf(p[1])
				));
			} else if (line.startsWith(PREFIX_FK)) {
				final String[] p = line.substring(FK_PREFIX_LEN).split(",", -1);
				final String parentSchema;
				final String parentTable;
				final String parentCols;
				final String onDelete;
				final String onUpdate;
				if (p.length >= FK_PARTS_WITH_SCHEMA) {
					parentSchema = p[3];
					parentTable = p[4];
					parentCols = p[5];
					onDelete = p[6];
					onUpdate = p[7];
				} else {
					if (p.length < LEGACY_FK_MIN_PARTS) {
						throw new IllegalArgumentException("Invalid catalog meta fk: " + sourceLabel);
					}
					final CatalogQualifiedName parentQn = CatalogQualifiedName.parse(
							p[3], CatalogPersistUtil.SCHEMA_PUBLIC);
					parentSchema = parentQn.schemaName();
					parentTable = parentQn.objectName();
					parentCols = p[4];
					onDelete = p[5];
					onUpdate = p[6];
				}
				fks.add(new FkDef(
						p[0],
						p[1],
						List.of(p[2].split("\\+")),
						parentSchema,
						parentTable,
						List.of(parentCols.split("\\+")),
						FkAction.valueOf(onDelete),
						FkAction.valueOf(onUpdate)
				));
			} else if (line.startsWith(PREFIX_CHECK)) {
				final String[] p = line.substring(PREFIX_CHECK.length()).split(",", 2);
				checks.add(new CheckDef(
						p[0],
						new String(Base64.getUrlDecoder().decode(p[1]), StandardCharsets.UTF_8)));
			}
		}
		if (table == null || cols.isEmpty()) {
			throw new IllegalArgumentException("Invalid catalog meta: " + sourceLabel);
		}
		final String catalogTable;
		if (schemaPart != null && !schemaPart.isBlank() && table.indexOf('.') < 0) {
			catalogTable = CatalogQualifiedName.of(schemaPart, table).catalogKey();
		} else {
			catalogTable = table;
		}
		return new TableSchema(catalogTable, cols, idxs, fks, checks, epoch);
	}
}
