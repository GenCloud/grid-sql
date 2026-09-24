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
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import org.genfork.grid.fs.GridFs;
import org.genfork.grid.mem.index.IndexType;
import org.genfork.grid.sql.udf.SqlMutatingUdf;
import org.genfork.grid.sql.udf.SqlTableUdf;
import org.genfork.grid.sql.udf.SqlUdf;
import org.genfork.grid.store.TableStore;

/**
 * In-memory table registry: name → schema + store handle.
 * <p>
 * Persist: optional snapshot under {@code dataDir}/catalog/ (DDL replay file).
 * Hot schema lookups go through bounded {@link CatalogMetaCache} (CRC fingerprint);
 * CREATE / ALTER / DROP invalidate or refresh the cache. Miss loads {@code *.meta} once.
 *
 * @author: GenCloud
 * @date: 2025/07
 * @since: 1.0
 */
public final class TableCatalog {
	public static final String COMPOSITE_FOREIGN_KEY_UNSUPPORTED =
			"composite FOREIGN KEY parent must match a UNIQUE index (or all PK columns) on the parent table";
	public static final String COMPOSITE_FOREIGN_KEY_NO_UNIQUE =
			"composite FOREIGN KEY parent columns do not match a UNIQUE index on parent";
	private static final String DDL_FILE = "ddl.sql";
	private static final String SCHEMAS_FILE = "schemas.list";
	private static final String META_SUFFIX = CatalogPersistUtil.META_SUFFIX;
	private static final String SCHEMA_PUBLIC = CatalogPersistUtil.SCHEMA_PUBLIC;
	private static final String ERR_DROP_PUBLIC = "cannot DROP SCHEMA public";
	private static final String ERR_SCHEMA_NOT_EMPTY_PREFIX = "schema ";
	private static final String ERR_SCHEMA_NOT_EMPTY_SUFFIX = " is not empty";
	private static final String ERR_SCHEMA_NOT_FOUND_PREFIX = "Schema not found: ";

	/**
	 * Catalog VIEW / MATERIALIZED VIEW definition (select body text).
	 * <p>
	 * For materialized views, {@link #lastRefreshEpoch()} / {@link #schemaEpochAtRefresh()}
	 * record staleness relative to catalog epochs (no auto-refresh on base writes).
	 *
	 * @author: GenCloud
	 * @date: 2025/07
	 * @since: 1.0
	 */
	public record ViewDef(
			String name,
			String selectSql,
			boolean materialized,
			long lastRefreshEpoch,
			long schemaEpochAtRefresh
	) {
		public ViewDef(String name, String selectSql, boolean materialized) {
			this(name, selectSql, materialized, 0L, 0L);
		}

		public ViewDef withRefresh(long refreshEpoch, long schemaEpoch) {
			return new ViewDef(name, selectSql, materialized, refreshEpoch, schemaEpoch);
		}
	}

	/**
	 * Catalog {@code CREATE FUNCTION} SPI binding (class/method, no string eval).
	 *
	 * @author: GenCloud
	 * @date: 2025/07
	 * @since: 1.0
	 */
	/**
	 * Catalog UDF: scalar expression or table-valued FROM source.
	 *
	 * @author: GenCloud
	 * @date: 2025/07
	 * @since: 1.0
	 */
	public record FunctionDef(
			String name,
			List<String> paramNames,
			List<SqlType> paramTypes,
			SqlType returnType,
			FunctionKind kind,
			List<String> tableColumnNames,
			List<SqlType> tableColumnTypes,
			String className,
			String methodName,
			SqlUdf udf,
			SqlTableUdf tableUdf,
			boolean mutating
	) {
		/** Scalar UDF convenience ctor; {@code mutating} from {@link SqlMutatingUdf}. */
		public FunctionDef(
				String name,
				List<String> paramNames,
				List<SqlType> paramTypes,
				SqlType returnType,
				String className,
				String methodName,
				SqlUdf udf
		) {
			this(name, paramNames, paramTypes, returnType, FunctionKind.SCALAR,
					List.of(), List.of(), className, methodName, udf, null,
					udf instanceof SqlMutatingUdf);
		}
	}

	private final ConcurrentHashMap<String, TableSchema> schemas = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, Boolean> creatingTables = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, Object> stores = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, ViewDef> views = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, FunctionDef> functions = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, List<TriggerDef>> triggersByTable = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, String> triggerTableByName = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, SequenceAllocator> sequences = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, TableAnalyzeStats> analyzeStats = new ConcurrentHashMap<>();
	private final Set<String> namedSchemas = ConcurrentHashMap.newKeySet();
	private final AtomicLong epochSequence = new AtomicLong(1L);
	/** Highest catalog DDL epoch successfully applied locally or from a peer. */
	private final AtomicLong maxAppliedDdlEpoch = new AtomicLong(0L);
	private final Path catalogDir;
	private final CatalogMetaCache metaCache;
	private final PrivilegeCatalog privileges;
	private BiConsumer<String, TableSchema> onCreate;
	private BiConsumer<String, TableSchema> onDrop;
	private final AtomicBoolean ddlReplay = new AtomicBoolean(false);

	public TableCatalog() {
		this(null, CatalogMetaCache.DEFAULT_SIZE);
	}

	public TableCatalog(Path dataDir) {
		this(dataDir, CatalogMetaCache.DEFAULT_SIZE);
	}

	/**
	 * @param catalogMetaCacheSize LRU capacity ({@code <= 0} = unbounded); see
	 *                             {@code grid.sql.catalog-meta-cache-size}
	 */
	public TableCatalog(Path dataDir, int catalogMetaCacheSize) {
		this.catalogDir = dataDir == null ? null : dataDir.resolve("catalog");
		this.metaCache = new CatalogMetaCache(catalogMetaCacheSize);
		if (catalogDir != null) {
			try {
				GridFs.createDirs(catalogDir);
			} catch (IOException e) {
				throw new IllegalStateException("Cannot create catalog dir " + catalogDir, e);
			}
		}
		this.privileges = new PrivilegeCatalog(catalogDir);
	}

	/** Bounded schema LRU used by lookups / information_schema. */
	public CatalogMetaCache metaCache() {
		return metaCache;
	}

	/** Durable RBAC catalog stored beside table metadata. */
	public PrivilegeCatalog privileges() {
		return privileges;
	}

	public void setOnCreate(BiConsumer<String, TableSchema> onCreate) {
		this.onCreate = onCreate;
	}

	public void setOnDrop(BiConsumer<String, TableSchema> onDrop) {
		this.onDrop = onDrop;
	}

	public long nextEpoch() {
		return epochSequence.getAndIncrement();
	}

	/** Next unused catalog epoch (does not advance). */
	public long currentEpoch() {
		return epochSequence.get();
	}

	/** Highest DDL schemaEpoch applied on this node (0 if none). */
	public long maxAppliedDdlEpoch() {
		return maxAppliedDdlEpoch.get();
	}

	/**
	 * Record that catalog DDL with {@code epoch} was applied. Monotonic.
	 */
	public void noteAppliedDdlEpoch(long epoch) {
		if (epoch <= 0L) {
			return;
		}
		maxAppliedDdlEpoch.updateAndGet(cur -> Math.max(cur, epoch));
	}

	/**
	 * Fail-closed: reject replicated DDL whose epoch is strictly behind what this node already applied.
	 */
	public void rejectIfStaleDdlEpoch(long schemaEpoch) {
		if (schemaEpoch <= 0L) {
			return;
		}
		final long applied = maxAppliedDdlEpoch.get();
		if (schemaEpoch < applied) {
			throw new IllegalStateException(
					"stale schema epoch localApplied=" + applied + " remote=" + schemaEpoch);
		}
	}

	/**
	 * Ensure the next {@link #nextEpoch()} is strictly greater than {@code epoch}
	 * (used after replicated DDL so local sequence stays aligned with peers).
	 */
	public void ensureEpochAtLeast(long epoch) {
		epochSequence.updateAndGet(cur -> Math.max(cur, epoch + 1L));
	}

	/**
	 * Register a SQL schema namespace ({@code CREATE SCHEMA}). Idempotent when already present.
	 *
	 * @return {@code true} if created, {@code false} if already existed
	 */
	public boolean createSchema(String schemaName, boolean ifNotExists) {
		Objects.requireNonNull(schemaName, "schemaName");
		final String key = key(schemaName);
		if (!namedSchemas.add(key)) {
			if (ifNotExists) {
				return false;
			}
			throw new IllegalStateException("Schema already exists: " + schemaName);
		}
		persistNamedSchemas();
		return true;
	}

	public boolean schemaExists(String schemaName) {
		if (schemaName == null) {
			return false;
		}
		final String k = key(schemaName);
		return SCHEMA_PUBLIC.equals(k) || namedSchemas.contains(k);
	}

	/**
	 * Snapshot of SQL schema namespaces: always includes {@code public}, plus named schemas
	 * and schema qualifiers discovered from registered table / view names.
	 */
	public Set<String> schemaNames() {
		final LinkedHashSet<String> out = new LinkedHashSet<>();
		out.add(SCHEMA_PUBLIC);
		out.addAll(namedSchemas);
		for (TableSchema schema : schemas.values()) {
			out.add(CatalogPersistUtil.schemaPartOf(schema.tableName()));
		}
		for (String viewName : views.keySet()) {
			out.add(CatalogPersistUtil.schemaPartOf(viewName));
		}
		return Collections.unmodifiableSet(out);
	}

	/** Snapshot of registered view names (lower keys as stored). */
	public Set<String> viewNames() {
		return Set.copyOf(views.keySet());
	}

	/**
	 * Drop a named schema namespace ({@code DROP SCHEMA … RESTRICT}).
	 * Fails if any table / view / sequence / function is keyed under that schema;
	 * {@code public} cannot be dropped.
	 *
	 * @return {@code true} if removed, {@code false} if missing and {@code ifExists}
	 */
	public boolean dropSchema(String schemaName, boolean ifExists) {
		return dropSchema(schemaName, ifExists, false);
	}

	/**
	 * @param emptyFirstForReplay when {@code true} (DDL journal replay only), drop contained
	 *                            objects first so append-only {@code DROP SCHEMA} after
	 *                            {@code CREATE TABLE} remains recoverable
	 */
	public boolean dropSchema(String schemaName, boolean ifExists, boolean emptyFirstForReplay) {
		Objects.requireNonNull(schemaName, "schemaName");
		final String k = key(schemaName);
		if (SCHEMA_PUBLIC.equals(k)) {
			throw new IllegalArgumentException(ERR_DROP_PUBLIC);
		}
		if (emptyFirstForReplay) {
			emptySchemaObjects(k);
		} else {
			rejectSchemaNotEmpty(schemaName, k);
		}
		if (!namedSchemas.remove(k)) {
			if (ifExists) {
				deletePersistedMetaForSchema(k);
				return false;
			}
			throw new IllegalStateException(ERR_SCHEMA_NOT_FOUND_PREFIX + schemaName);
		}
		deletePersistedMetaForSchema(k);
		persistNamedSchemas();
		return true;
	}

	/**
	 * Disk hygiene for {@code DROP TABLE IF EXISTS} when the table is already gone in RAM.
	 */
	public void deletePersistedTableMeta(String tableName) {
		deletePersisted(tableName);
	}

	/**
	 * Remove orphan {@code *.meta} whose schema is no longer registered (post-replay).
	 *
	 * @return number of files deleted
	 */
	public int pruneOrphanMetaFiles() {
		if (catalogDir == null) {
			return 0;
		}
		int removed = 0;
		try (DirectoryStream<Path> stream = GridFs.newDirectoryStream(catalogDir, "*" + META_SUFFIX)) {
			for (Path meta : stream) {
				final Path fileName = meta.getFileName();
				if (fileName == null) {
					continue;
				}
				final String name = fileName.toString();
				if (PrivilegeCatalog.META_FILE_NAME.equalsIgnoreCase(name)) {
					continue;
				}
				if (!name.endsWith(META_SUFFIX)) {
					continue;
				}
				final String tableKey = name.substring(0, name.length() - META_SUFFIX.length());
				final String schemaPart = CatalogPersistUtil.schemaPartOf(tableKey);
				if (SCHEMA_PUBLIC.equals(schemaPart)) {
					if (exists(tableKey)) {
						continue;
					}
					GridFs.deleteIfExists(meta);
					removed++;
					continue;
				}
				if (namedSchemas.contains(schemaPart) && exists(tableKey)) {
					continue;
				}
				if (namedSchemas.contains(schemaPart) && !exists(tableKey)) {
					/* schema alive but table dropped — orphan meta */
					GridFs.deleteIfExists(meta);
					removed++;
					continue;
				}
				if (!namedSchemas.contains(schemaPart)) {
					GridFs.deleteIfExists(meta);
					removed++;
				}
			}
		} catch (IOException e) {
			throw new IllegalStateException("Failed to prune orphan meta under " + catalogDir, e);
		}
		return removed;
	}

	/**
	 * Whether a persisted meta table should be hydrated after DDL replay.
	 */
	public boolean shouldHydrateMeta(TableSchema meta) {
		if (meta == null) {
			return false;
		}
		final String table = meta.tableName();
		final String schemaPart = CatalogPersistUtil.schemaPartOf(table);
		if (!SCHEMA_PUBLIC.equals(schemaPart) && !namedSchemas.contains(schemaPart)) {
			return false;
		}
		return !exists(table);
	}

	/**
	 * Rewrite {@code ddl.sql} from the live catalog (bootstrap compaction).
	 */
	public synchronized void rewriteCompactedDdl() {
		if (ddlReplay.get() || catalogDir == null) {
			return;
		}
		final List<String> lines = CatalogDdlCompactor.compactLines(this);
		try {
			GridFs.createDirs(catalogDir);
			final StringBuilder body = new StringBuilder();
			for (String line : lines) {
				body.append(line).append(System.lineSeparator());
			}
			GridFs.writeAtomic(catalogDir.resolve(DDL_FILE), body.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to rewrite compacted DDL", e);
		}
	}

	private void rejectSchemaNotEmpty(String schemaName, String schemaKey) {
		if (hasObjectInSchema(schemas.keySet(), schemaKey)
				|| hasObjectInSchema(views.keySet(), schemaKey)
				|| hasObjectInSchema(sequences.keySet(), schemaKey)
				|| hasObjectInSchema(functions.keySet(), schemaKey)) {
			throw new IllegalStateException(
					ERR_SCHEMA_NOT_EMPTY_PREFIX + schemaName + ERR_SCHEMA_NOT_EMPTY_SUFFIX);
		}
	}

	private static boolean hasObjectInSchema(Set<String> keys, String schemaKey) {
		for (String objectKey : keys) {
			if (CatalogPersistUtil.belongsToSchema(objectKey, schemaKey)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Drop all catalog objects in {@code schemaKey} (replay-only path before DROP SCHEMA).
	 */
	private void emptySchemaObjects(String schemaKey) {
		final ArrayList<String> tableKeys = new ArrayList<>();
		for (String tableKey : schemas.keySet()) {
			if (CatalogPersistUtil.belongsToSchema(tableKey, schemaKey)) {
				tableKeys.add(tableKey);
			}
		}
		for (String tableKey : tableKeys) {
			dropTable(tableKey);
		}
		final ArrayList<String> viewKeys = new ArrayList<>();
		for (String viewKey : views.keySet()) {
			if (CatalogPersistUtil.belongsToSchema(viewKey, schemaKey)) {
				viewKeys.add(viewKey);
			}
		}
		for (String viewKey : viewKeys) {
			dropView(viewKey, false);
		}
		final ArrayList<String> seqKeys = new ArrayList<>();
		for (String seqKey : sequences.keySet()) {
			if (CatalogPersistUtil.belongsToSchema(seqKey, schemaKey)) {
				seqKeys.add(seqKey);
			}
		}
		for (String seqKey : seqKeys) {
			dropSequence(seqKey, false);
		}
		final ArrayList<String> fnKeys = new ArrayList<>();
		for (String fnKey : functions.keySet()) {
			if (CatalogPersistUtil.belongsToSchema(fnKey, schemaKey)) {
				fnKeys.add(fnKey);
			}
		}
		for (String fnKey : fnKeys) {
			dropFunction(fnKey, false);
		}
	}

	private void deletePersistedMetaForSchema(String schemaKey) {
		if (catalogDir == null) {
			return;
		}
		try (DirectoryStream<Path> stream = GridFs.newDirectoryStream(catalogDir, "*" + META_SUFFIX)) {
			for (Path meta : stream) {
				final Path fileName = meta.getFileName();
				if (fileName == null) {
					continue;
				}
				final String name = fileName.toString();
				if (PrivilegeCatalog.META_FILE_NAME.equalsIgnoreCase(name)) {
					continue;
				}
				if (!name.endsWith(META_SUFFIX)) {
					continue;
				}
				final String tableKey = name.substring(0, name.length() - META_SUFFIX.length());
				if (CatalogPersistUtil.belongsToSchema(tableKey, schemaKey)) {
					GridFs.deleteIfExists(meta);
				}
			}
		} catch (IOException ignored) {
			/* best-effort */
		}
	}

	public TableSchema createTable(TableSchema schema) {
		Objects.requireNonNull(schema, "schema");
		rejectFkCycle(schema);
		rejectUnknownFkParents(schema);
		final String key = key(schema.tableName());
		if (views.containsKey(key)) {
			throw new IllegalStateException("View already exists: " + schema.tableName());
		}
		if (schemas.containsKey(key) || creatingTables.putIfAbsent(key, Boolean.TRUE) != null) {
			throw new IllegalStateException("Table already exists: " + schema.tableName());
		}
		try {
			final TableSchema prev = schemas.putIfAbsent(key, schema);
			if (prev != null) {
				throw new IllegalStateException("Table already exists: " + schema.tableName());
			}
			metaCache.put(key, schema);
			persistSchema(schema);
			if (onCreate != null) {
				onCreate.accept(schema.tableName(), schema);
			}
		} catch (RuntimeException ex) {
			schemas.remove(key, schema);
			metaCache.invalidate(key);
			stores.remove(key);
			throw ex;
		} finally {
			creatingTables.remove(key);
		}
		return schema;
	}

	/**
	 * Register a sequence allocator. Idempotent when {@code ifNotExists} and already present.
	 *
	 * @return {@code true} if created
	 */
	public boolean createSequence(SequenceDef def, boolean ifNotExists) {
		Objects.requireNonNull(def, "def");
		final String k = key(def.name());
		final SequenceAllocator alloc = new SequenceAllocator(def, catalogDir);
		final SequenceAllocator prev = sequences.putIfAbsent(k, alloc);
		if (prev != null) {
			if (ifNotExists) {
				return false;
			}
			throw new IllegalStateException("Sequence already exists: " + def.name());
		}
		return true;
	}

	public boolean dropSequence(String name, boolean ifExists) {
		final String k = key(name);
		final SequenceAllocator removed = sequences.remove(k);
		if (removed == null) {
			if (ifExists) {
				return false;
			}
			throw new IllegalStateException("Sequence not found: " + name);
		}
		removed.deleteMeta();
		return true;
	}

	public SequenceAllocator getSequence(String name) {
		if (name == null) {
			return null;
		}
		return sequences.get(key(name));
	}

	public SequenceAllocator requireSequence(String name) {
		final SequenceAllocator alloc = getSequence(name);
		if (alloc == null) {
			throw new IllegalStateException("Sequence not found: " + name);
		}
		return alloc;
	}

	/** Allocate next sequence value (durable hi-water when catalogDir bound). */
	public long nextVal(String name) {
		return requireSequence(name).nextVal();
	}

	/**
	 * Flush dirty sequence reclaim sidecars (TX commit / shutdown boundary).
	 */
	public void flushSequencesIfDirty() {
		for (SequenceAllocator alloc : sequences.values()) {
			alloc.flushIfDirty();
		}
	}

	public Collection<SequenceDef> sequenceDefs() {
		final ArrayList<SequenceDef> out = new ArrayList<>(sequences.size());
		for (SequenceAllocator alloc : sequences.values()) {
			out.add(alloc.def());
		}
		return List.copyOf(out);
	}

	/**
	 * Reject CREATE TABLE FK graph cycles (including self-reference cycles via other tables).
	 * Self-FK on the same table is allowed only as a tree edge checked at DML time —
	 * mutual cycles A→B→A are rejected.
	 */
	public void rejectFkCycle(TableSchema schema) {
		Objects.requireNonNull(schema, "schema");
		if (schema.foreignKeys().isEmpty()) {
			return;
		}
		final String self = key(schema.tableName());
		for (FkDef fk : schema.foreignKeys()) {
			if (key(fk.parentTable()).equals(self)) {
				throw new IllegalArgumentException(
						"cyclic FOREIGN KEY rejected for table " + schema.tableName());
			}
		}
		final Map<String, List<String>> edges = new HashMap<>();
		for (TableSchema existing : schemas.values()) {
			addFkEdges(edges, existing);
		}
		addFkEdges(edges, schema);
		if (hasCycleFrom(edges, self, new LinkedHashSet<>())) {
			throw new IllegalArgumentException(
					"cyclic FOREIGN KEY rejected for table " + schema.tableName());
		}
	}

	private void rejectUnknownFkParents(TableSchema schema) {
		for (FkDef fk : schema.foreignKeys()) {
			final String parentKey = key(fk.parentTable());
			if (parentKey.equals(key(schema.tableName()))) {
				continue;
			}
			if (!schemas.containsKey(parentKey)) {
				throw new IllegalArgumentException(
						"FK " + fk.name() + " references unknown table " + fk.parentTable());
			}
			final TableSchema parent = schemas.get(parentKey);
			for (String col : fk.parentColumns()) {
				parent.requireColumn(col);
			}
			if (fk.parentColumns().size() == 1) {
				if (parent.pkColumns().size() != 1
						|| !parent.pkColumn().name().equalsIgnoreCase(fk.parentColumns().getFirst())) {
					throw new IllegalArgumentException(
							"FK " + fk.name() + " parent columns must match the full PRIMARY KEY");
				}
				continue;
			}
			// Multi-column FK: order-sensitive match against a UNIQUE (STRICT) index on parent,
			// or against the ordered list of all primary-key columns when multi-PK exists.
			if (!parentHasUniqueOrPkMatch(parent, fk.parentColumns())) {
				throw new IllegalArgumentException(
						COMPOSITE_FOREIGN_KEY_NO_UNIQUE + ": " + fk.name());
			}
		}
	}

	/**
	 * Order-sensitive column list equality (case-insensitive names) against a STRICT unique
	 * index, or against every PK column in declaration order when the parent exposes multi-PK.
	 */
	private static boolean parentHasUniqueOrPkMatch(TableSchema parent, List<String> parentColumns) {
		for (IndexDef idx : parent.indexes()) {
			if (idx.kind() != IndexType.STRICT) {
				continue;
			}
			if (columnsEqualIgnoreCaseOrdered(idx.columns(), parentColumns)) {
				return true;
			}
		}
		final List<String> pkCols = new ArrayList<>();
		for (ColumnDef col : parent.columns()) {
			if (col.primaryKey()) {
				pkCols.add(col.name());
			}
		}
		return pkCols.size() > 1 && columnsEqualIgnoreCaseOrdered(pkCols, parentColumns);
	}

	private static boolean columnsEqualIgnoreCaseOrdered(List<String> left, List<String> right) {
		if (left.size() != right.size()) {
			return false;
		}
		for (int i = 0; i < left.size(); i++) {
			if (!left.get(i).equalsIgnoreCase(right.get(i))) {
				return false;
			}
		}
		return true;
	}

	private static void addFkEdges(Map<String, List<String>> edges, TableSchema schema) {
		final String from = key(schema.tableName());
		for (FkDef fk : schema.foreignKeys()) {
			final String parent = key(fk.parentTable());
			if (parent.equals(from)) {
				continue;
			}
			edges.computeIfAbsent(from, _ -> new ArrayList<>()).add(parent);
		}
	}

	private static boolean hasCycleFrom(
			Map<String, List<String>> edges,
			String node,
			LinkedHashSet<String> stack
	) {
		if (!stack.add(node)) {
			return true;
		}
		final List<String> next = edges.getOrDefault(node, List.of());
		for (String child : next) {
			if (hasCycleFrom(edges, child, stack)) {
				return true;
			}
		}
		stack.remove(node);
		return false;
	}

	/** True when any registered table references {@code parentTable} via FK. */
	public boolean isReferencedByForeignKey(String parentTable) {
		final String parentKey = key(parentTable);
		for (TableSchema schema : schemas.values()) {
			for (FkDef fk : schema.foreignKeys()) {
				if (key(fk.parentTable()).equals(parentKey)) {
					return true;
				}
			}
		}
		return false;
	}

	/** Child-table FK defs that reference {@code parentTable}. */
	public List<FkDef> foreignKeysReferencing(String parentTable) {
		final String parentKey = key(parentTable);
		final ArrayList<FkDef> out = new ArrayList<>();
		for (TableSchema schema : schemas.values()) {
			for (FkDef fk : schema.foreignKeys()) {
				if (key(fk.parentTable()).equals(parentKey)) {
					out.add(fk);
				}
			}
		}
		return List.copyOf(out);
	}

	public TableSchema dropTable(String tableName) {
		final String key = key(tableName);
		if (isReferencedByForeignKey(tableName)) {
			throw new IllegalStateException(
					"cannot DROP TABLE " + tableName + ": referenced by FOREIGN KEY");
		}
		final TableSchema removed = schemas.remove(key);
		if (removed == null) {
			throw new IllegalStateException("Table not found: " + tableName);
		}
		metaCache.invalidate(key);
		final Object store = stores.remove(key);
		if (store instanceof TableStore tableStore) {
			tableStore.close();
		}
		analyzeStats.remove(key);
		final List<TriggerDef> removedTriggers = triggersByTable.remove(key);
		if (removedTriggers != null) {
			for (TriggerDef def : removedTriggers) {
				triggerTableByName.remove(key(def.name()));
			}
		}
		deletePersisted(tableName);
		deleteAnalyzePersisted(key);
		if (onDrop != null) {
			onDrop.accept(tableName, removed);
		}
		return removed;
	}

	/**
	 * Resolve table schema: LRU hit → registry → parse {@code *.meta} once (when catalogDir bound).
	 */
	public TableSchema getSchema(String tableName) {
		if (tableName == null) {
			return null;
		}
		final String k = key(tableName);
		final CatalogMetaCache.CachedMeta cached = metaCache.get(k);
		if (cached != null) {
			return cached.schema();
		}
		final TableSchema registered = schemas.get(k);
		if (registered != null) {
			metaCache.put(k, registered);
			return registered;
		}
		final TableSchema fromDisk = loadMetaSchemaOnce(k);
		if (fromDisk != null) {
			metaCache.put(k, fromDisk);
		}
		return fromDisk;
	}

	public TableSchema requireSchema(String tableName) {
		final TableSchema schema = getSchema(tableName);
		if (schema == null) {
			throw new IllegalArgumentException("Unknown table: " + tableName);
		}
		return schema;
	}

	/**
	 * Persist / replace crude {@code ANALYZE} stats (memory + optional catalog/stats sidecar).
	 */
	public void putAnalyzeStats(String tableName, TableAnalyzeStats stats) {
		Objects.requireNonNull(tableName, "tableName");
		Objects.requireNonNull(stats, "stats");
		final String k = key(tableName);
		analyzeStats.put(k, stats);
		persistAnalyzeStats(k, stats);
	}

	/** In-memory ANALYZE hints, or {@code null} when never analyzed. */
	public TableAnalyzeStats getAnalyzeStats(String tableName) {
		if (tableName == null) {
			return null;
		}
		return analyzeStats.get(key(tableName));
	}

	/** Load ANALYZE sidecar into memory (best-effort; missing file = no-op). */
	public void loadAnalyzeStats(String tableName) {
		if (catalogDir == null || tableName == null) {
			return;
		}
		final String k = key(tableName);
		final Path file = statsDir().resolve(safeFileName(k) + TableAnalyzeStats.FILE_SUFFIX);
		if (!GridFs.isRegularFile(file)) {
			return;
		}
		try {
			final String body = GridFs.readString(file, StandardCharsets.UTF_8);
			analyzeStats.put(k, TableAnalyzeStats.parse(body));
		} catch (IOException | RuntimeException ignored) {
			// best-effort hydrate
		}
	}

	private void persistAnalyzeStats(String tableKey, TableAnalyzeStats stats) {
		if (catalogDir == null) {
			return;
		}
		try {
			final Path dir = statsDir();
			GridFs.createDirs(dir);
			final Path file = dir.resolve(safeFileName(tableKey) + TableAnalyzeStats.FILE_SUFFIX);
			GridFs.writeAtomic(file, stats.toFileBody(), StandardCharsets.UTF_8);
		} catch (IOException ex) {
			throw new IllegalStateException("Failed to persist ANALYZE stats for " + tableKey, ex);
		}
	}

	private Path statsDir() {
		return catalogDir.resolve("stats");
	}

	private void deleteAnalyzePersisted(String tableKey) {
		if (catalogDir == null || tableKey == null) {
			return;
		}
		try {
			GridFs.deleteIfExists(statsDir().resolve(safeFileName(tableKey) + TableAnalyzeStats.FILE_SUFFIX));
		} catch (IOException ignored) {
			// best-effort
		}
	}

	private static String safeFileName(String tableKey) {
		return tableKey.replace('.', '_').replace('/', '_');
	}

	public boolean exists(String tableName) {
		final String k = key(tableName);
		return schemas.containsKey(k) || creatingTables.containsKey(k);
	}

	/**
	 * Register a VIEW or MATERIALIZED VIEW. Non-materialized names must not collide with a table;
	 * materialized views share the name with their backing table.
	 */
	public ViewDef createView(String name, String selectSql, boolean materialized) {
		return createView(name, selectSql, materialized, 0L, 0L);
	}

	/**
	 * Register a VIEW or MATERIALIZED VIEW. Non-materialized names must not collide with a table;
	 * materialized views share the name with their backing table.
	 *
	 * @param lastRefreshEpoch     epoch of last REFRESH / initial populate (0 if none)
	 * @param schemaEpochAtRefresh catalog schema epoch observed at that refresh
	 */
	public ViewDef createView(
			String name,
			String selectSql,
			boolean materialized,
			long lastRefreshEpoch,
			long schemaEpochAtRefresh
	) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(selectSql, "selectSql");
		final String k = key(name);
		if (!materialized && (schemas.containsKey(k) || creatingTables.containsKey(k))) {
			throw new IllegalStateException("Table already exists: " + name);
		}
		if (materialized && !schemas.containsKey(k) && !creatingTables.containsKey(k)) {
			throw new IllegalStateException("Materialized view backing table missing: " + name);
		}
		final ViewDef def = new ViewDef(name, selectSql, materialized, lastRefreshEpoch, schemaEpochAtRefresh);
		final ViewDef prev = views.putIfAbsent(k, def);
		if (prev != null) {
			throw new IllegalStateException("View already exists: " + name);
		}
		return def;
	}

	/**
	 * Update materialized-view refresh timestamps (staleness metadata only; no auto-refresh).
	 */
	public ViewDef noteViewRefreshed(String name, long refreshEpoch, long schemaEpoch) {
		Objects.requireNonNull(name, "name");
		final String k = key(name);
		final ViewDef existing = views.get(k);
		if (existing == null || !existing.materialized()) {
			throw new IllegalStateException("Materialized view not found: " + name);
		}
		final ViewDef updated = existing.withRefresh(refreshEpoch, schemaEpoch);
		views.put(k, updated);
		return updated;
	}

	/**
	 * Drop a view by name.
	 *
	 * @return removed def, or {@code null} when missing and {@code ifExists}
	 */
	public ViewDef dropView(String name, boolean ifExists) {
		Objects.requireNonNull(name, "name");
		final String k = key(name);
		final ViewDef removed = views.remove(k);
		if (removed == null) {
			if (ifExists) {
				return null;
			}
			throw new IllegalStateException("View not found: " + name);
		}
		return removed;
	}

	public ViewDef getView(String name) {
		if (name == null) {
			return null;
		}
		return views.get(key(name));
	}

	/**
	 * Non-materialized view name → defining SELECT (lower keys) for {@link org.genfork.grid.sql.SqlNamedQueryExpand}.
	 */
	public Map<String, String> viewSelectBodies() {
		if (views.isEmpty()) {
			return Map.of();
		}
		final Map<String, String> out = new HashMap<>(views.size() * 2);
		for (Map.Entry<String, ViewDef> e : views.entrySet()) {
			if (!e.getValue().materialized()) {
				out.put(e.getKey(), e.getValue().selectSql());
			}
		}
		return Map.copyOf(out);
	}

	/**
	 * Register a scalar or table-valued UDF next to VIEW/MV definitions.
	 */
	public FunctionDef createFunction(FunctionDef def) {
		Objects.requireNonNull(def, "def");
		Objects.requireNonNull(def.name(), "name");
		Objects.requireNonNull(def.kind(), "kind");
		if (def.kind() == FunctionKind.SCALAR) {
			Objects.requireNonNull(def.udf(), "udf");
		} else if (def.kind() == FunctionKind.TABLE) {
			Objects.requireNonNull(def.tableUdf(), "tableUdf");
		}
		final String k = key(def.name());
		final FunctionDef prev = functions.putIfAbsent(k, def);
		if (prev != null) {
			throw new IllegalStateException("Function already exists: " + def.name());
		}
		return def;
	}

	/**
	 * Drop a UDF by name.
	 *
	 * @return removed def, or {@code null} when missing and {@code ifExists}
	 */
	public FunctionDef dropFunction(String name, boolean ifExists) {
		Objects.requireNonNull(name, "name");
		final String k = key(name);
		final FunctionDef removed = functions.remove(k);
		if (removed == null) {
			if (ifExists) {
				return null;
			}
			throw new IllegalStateException("Function not found: " + name);
		}
		return removed;
	}

	public FunctionDef getFunction(String name) {
		if (name == null) {
			return null;
		}
		return functions.get(key(name));
	}

	public FunctionDef requireFunction(String name) {
		final FunctionDef def = getFunction(name);
		if (def == null) {
			throw new IllegalStateException("Function not found: " + name);
		}
		return def;
	}

	/**
	 * Register a row trigger on a table (name unique across catalog).
	 */
	public TriggerDef createTrigger(TriggerDef def) {
		Objects.requireNonNull(def, "def");
		final String nameKey = key(def.name());
		final String tableKey = key(def.table());
		if (!schemas.containsKey(tableKey)) {
			throw new IllegalStateException("Table not found: " + def.table());
		}
		final String prevTable = triggerTableByName.putIfAbsent(nameKey, tableKey);
		if (prevTable != null) {
			throw new IllegalStateException("Trigger already exists: " + def.name());
		}
		triggersByTable.compute(tableKey, (k, existing) -> {
			final List<TriggerDef> next = existing == null
					? new ArrayList<>()
					: new ArrayList<>(existing);
			next.add(def);
			return List.copyOf(next);
		});
		return def;
	}

	/**
	 * Drop a trigger by name; optional table must match when provided.
	 *
	 * @return removed def, or {@code null} when missing and {@code ifExists}
	 */
	public TriggerDef dropTrigger(String name, boolean ifExists, String tableOrNull) {
		Objects.requireNonNull(name, "name");
		final String nameKey = key(name);
		final String tableKey = triggerTableByName.get(nameKey);
		if (tableKey == null) {
			if (ifExists) {
				return null;
			}
			throw new IllegalStateException("Trigger not found: " + name);
		}
		if (tableOrNull != null && !tableKey.equals(key(tableOrNull))) {
			if (ifExists) {
				return null;
			}
			throw new IllegalStateException(
					"Trigger " + name + " is not on table " + tableOrNull);
		}
		triggerTableByName.remove(nameKey);
		final TriggerDef[] removed = new TriggerDef[1];
		triggersByTable.computeIfPresent(tableKey, (k, existing) -> {
			final List<TriggerDef> next = new ArrayList<>(existing.size());
			for (TriggerDef def : existing) {
				if (key(def.name()).equals(nameKey)) {
					removed[0] = def;
				} else {
					next.add(def);
				}
			}
			return next.isEmpty() ? null : List.copyOf(next);
		});
		if (removed[0] == null) {
			if (ifExists) {
				return null;
			}
			throw new IllegalStateException("Trigger not found: " + name);
		}
		return removed[0];
	}

	/** Triggers registered on {@code table} (empty when none). */
	public List<TriggerDef> triggersForTable(String table) {
		if (table == null) {
			return List.of();
		}
		final List<TriggerDef> list = triggersByTable.get(key(table));
		return list == null ? List.of() : list;
	}

	public TriggerDef getTrigger(String name) {
		if (name == null) {
			return null;
		}
		final String tableKey = triggerTableByName.get(key(name));
		if (tableKey == null) {
			return null;
		}
		final List<TriggerDef> list = triggersByTable.get(tableKey);
		if (list == null) {
			return null;
		}
		final String nameKey = key(name);
		for (TriggerDef def : list) {
			if (key(def.name()).equals(nameKey)) {
				return def;
			}
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	public <T> T getStore(String tableName) {
		return (T) stores.get(key(tableName));
	}

	public void bindStore(String tableName, Object store) {
		stores.put(key(tableName), Objects.requireNonNull(store));
	}

	/** Snapshot of registered table names (for harness cleanup). */
	public Set<String> tableNames() {
		final LinkedHashSet<String> names = new LinkedHashSet<>(schemas.size());
		for (TableSchema schema : schemas.values()) {
			names.add(schema.tableName());
		}
		return Collections.unmodifiableSet(names);
	}

	/** Replace schema after CREATE/DROP INDEX / ALTER (store already bound). */
	public void replaceSchema(TableSchema schema) {
		Objects.requireNonNull(schema, "schema");
		final String k = key(schema.tableName());
		if (!schemas.containsKey(k)) {
			throw new IllegalStateException("Table not found: " + schema.tableName());
		}
		schemas.put(k, schema);
		metaCache.put(k, schema);
		persistSchema(schema);
	}

	/**
	 * Snapshot of registered schemas; each entry is served via {@link CatalogMetaCache}
	 * (information_schema / JDBC metadata path).
	 */
	public Collection<TableSchema> schemas() {
		final ArrayList<TableSchema> out = new ArrayList<>(schemas.size());
		for (Map.Entry<String, TableSchema> e : schemas.entrySet()) {
			out.add(schemaViaCache(e.getKey(), e.getValue()));
		}
		return List.copyOf(out);
	}

	public Map<String, TableSchema> schemaMap() {
		final HashMap<String, TableSchema> out = new HashMap<>(schemas.size() * 2);
		for (Map.Entry<String, TableSchema> e : schemas.entrySet()) {
			out.put(e.getKey(), schemaViaCache(e.getKey(), e.getValue()));
		}
		return Map.copyOf(out);
	}

	private TableSchema schemaViaCache(String tableKey, TableSchema registered) {
		final CatalogMetaCache.CachedMeta cached = metaCache.get(tableKey);
		if (cached != null) {
			return cached.schema();
		}
		metaCache.put(tableKey, registered);
		return registered;
	}

	/**
	 * Parse persisted {@code *.meta} for {@code tableKey} once (GridFs when available; else NIO).
	 */
	private TableSchema loadMetaSchemaOnce(String tableKey) {
		if (catalogDir == null || tableKey == null) {
			return null;
		}
		final Path meta = catalogDir.resolve(tableKey + META_SUFFIX);
		if (!GridFs.isRegularFile(meta)) {
			return null;
		}
		try {
			final byte[] bytes = GridFs.readAll(meta);
			return parseMetaBytes(bytes, meta.toString());
		} catch (IOException e) {
			throw new IllegalStateException("Failed to read catalog meta " + meta, e);
		}
	}

	/** Reload schemas from catalog snapshot files (DDL text lines). */
	public List<String> loadPersistedDdl() {
		if (catalogDir == null || !GridFs.isDirectory(catalogDir)) {
			return List.of();
		}
		final Path ddl = catalogDir.resolve(DDL_FILE);
		if (!GridFs.isRegularFile(ddl)) {
			return List.of();
		}
		try {
			return GridFs.readLines(ddl, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to read catalog DDL " + ddl, e);
		}
	}

	/**
	 * Named schema namespaces persisted beside {@code ddl.sql}
	 * (survives even if CREATE SCHEMA lines were truncated).
	 */
	public List<String> loadPersistedNamedSchemas() {
		if (catalogDir == null || !GridFs.isDirectory(catalogDir)) {
			return List.of();
		}
		final Path file = catalogDir.resolve(SCHEMAS_FILE);
		if (!GridFs.isRegularFile(file)) {
			return List.of();
		}
		try {
			return GridFs.readLines(file, StandardCharsets.UTF_8).stream()
					.map(String::trim)
					.filter(s -> !s.isEmpty())
					.toList();
		} catch (IOException e) {
			throw new IllegalStateException("Failed to read " + file, e);
		}
	}

	/**
	 * Hydrate tables from {@code *.meta} snapshots when missing after DDL replay
	 * (or when {@code ddl.sql} is absent).
	 */
	public List<TableSchema> loadPersistedMetaSchemas() {
		if (catalogDir == null || !GridFs.isDirectory(catalogDir)) {
			return List.of();
		}
		final List<TableSchema> out = new ArrayList<>();
		try (DirectoryStream<Path> stream = GridFs.newDirectoryStream(catalogDir, "*" + META_SUFFIX)) {
			for (Path meta : stream) {
				final Path fileName = meta.getFileName();
				if (fileName != null
						&& PrivilegeCatalog.META_FILE_NAME.equalsIgnoreCase(fileName.toString())) {
					continue;
				}
				out.add(parseMeta(meta));
			}
		} catch (IOException e) {
			throw new IllegalStateException("Failed to scan catalog meta under " + catalogDir, e);
		}
		return out;
	}

	/** Suppress {@link #appendDdl} while replaying persisted catalog SQL. */
	public void beginDdlReplay() {
		ddlReplay.set(true);
	}

	public void endDdlReplay() {
		ddlReplay.set(false);
	}

	public boolean isDdlReplay() {
		return ddlReplay.get();
	}

	public synchronized void appendDdl(String ddlStatement) {
		if (ddlReplay.get()) {
			return;
		}
		if (catalogDir == null || ddlStatement == null || ddlStatement.isBlank()) {
			return;
		}
		try {
			GridFs.appendString(catalogDir.resolve(DDL_FILE), ddlStatement.trim() + System.lineSeparator());
		} catch (IOException e) {
			throw new IllegalStateException("Failed to persist DDL", e);
		}
	}

	private void persistNamedSchemas() {
		if (ddlReplay.get() || catalogDir == null) {
			return;
		}
		try {
			GridFs.createDirs(catalogDir);
			final String body = namedSchemas.stream()
					.sorted()
					.collect(Collectors.joining(System.lineSeparator()));
			final String withNl = body.isEmpty() ? "" : body + System.lineSeparator();
			GridFs.writeAtomic(catalogDir.resolve(SCHEMAS_FILE), withNl, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to persist named schemas", e);
		}
	}

	private void persistSchema(TableSchema schema) {
		if (catalogDir == null) {
			return;
		}
		try {
			GridFs.createDirs(catalogDir);
			final Path meta = catalogDir.resolve(schema.tableName().toLowerCase(Locale.ROOT) + ".meta");
			final StringBuilder sb = new StringBuilder();
			sb.append("table=").append(schema.tableName()).append('\n');
			sb.append("epoch=").append(schema.schemaEpoch()).append('\n');
			for (ColumnDef col : schema.columns()) {
				sb.append("col=")
						.append(col.name()).append(',')
						.append(col.type().name()).append(',')
						.append(col.nullable()).append(',')
						.append(col.primaryKey()).append(',')
						.append(col.externalOrder()).append(',')
						.append(col.identity()).append(',')
						.append(col.identitySequence() == null ? "" : col.identitySequence())
						.append('\n');
			}
			for (IndexDef idx : schema.indexes()) {
				sb.append("idx=")
						.append(idx.name()).append(',')
						.append(idx.kind().name()).append(',')
						.append(String.join("+", idx.columns())).append('\n');
			}
			for (FkDef fk : schema.foreignKeys()) {
				sb.append("fk=")
						.append(fk.name()).append(',')
						.append(fk.childTable()).append(',')
						.append(String.join("+", fk.childColumns())).append(',')
						.append(fk.parentTable()).append(',')
						.append(String.join("+", fk.parentColumns())).append(',')
						.append(fk.onDelete().name()).append(',')
						.append(fk.onUpdate().name())
						.append('\n');
			}
			for (CheckDef check : schema.checks()) {
				sb.append("check=")
						.append(check.name()).append(',')
						.append(Base64.getUrlEncoder().withoutPadding().encodeToString(
								check.expressionSql().getBytes(StandardCharsets.UTF_8)))
						.append('\n');
			}
			GridFs.writeAtomic(meta, sb.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to persist schema " + schema.tableName(), e);
		}
	}

	private void deletePersisted(String tableName) {
		if (catalogDir == null) {
			return;
		}
		try {
			GridFs.deleteIfExists(catalogDir.resolve(tableName.toLowerCase(Locale.ROOT) + ".meta"));
		} catch (IOException ignored) {
			// best-effort
		}
	}

	private static String key(String tableName) {
		return tableName.toLowerCase(Locale.ROOT);
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
		long epoch = 1L;
		final List<ColumnDef> cols = new ArrayList<>();
		final List<IndexDef> idxs = new ArrayList<>();
		final List<FkDef> fks = new ArrayList<>();
		final List<CheckDef> checks = new ArrayList<>();
		for (String line : lines) {
			if (line.isEmpty()) {
				continue;
			}
			if (line.startsWith("table=")) {
				table = line.substring(6);
			} else if (line.startsWith("epoch=")) {
				epoch = Long.parseLong(line.substring(6));
			} else if (line.startsWith("col=")) {
				final String[] p = line.substring(4).split(",", -1);
				final boolean identity = p.length > 5 && Boolean.parseBoolean(p[5]);
				final String seq = p.length > 6 && !p[6].isEmpty() ? p[6] : null;
				cols.add(new ColumnDef(
						p[0],
						SqlType.valueOf(p[1]),
						Boolean.parseBoolean(p[2]),
						cols.size(),
						Boolean.parseBoolean(p[3]),
						Boolean.parseBoolean(p[4]),
						identity,
						seq
				));
			} else if (line.startsWith("idx=")) {
				final String[] p = line.substring(4).split(",", 3);
				idxs.add(new IndexDef(
						p[0],
						List.of(p[2].split("\\+")),
						IndexType.valueOf(p[1])
				));
			} else if (line.startsWith("fk=")) {
				final String[] p = line.substring(3).split(",", -1);
				fks.add(new FkDef(
						p[0],
						p[1],
						List.of(p[2].split("\\+")),
						p[3],
						List.of(p[4].split("\\+")),
						FkAction.valueOf(p[5]),
						FkAction.valueOf(p[6])
				));
			} else if (line.startsWith("check=")) {
				final String[] p = line.substring("check=".length()).split(",", 2);
				checks.add(new CheckDef(
						p[0],
						new String(Base64.getUrlDecoder().decode(p[1]), StandardCharsets.UTF_8)));
			}
		}
		if (table == null || cols.isEmpty()) {
			throw new IllegalArgumentException("Invalid catalog meta: " + sourceLabel);
		}
		return new TableSchema(table, cols, idxs, fks, checks, epoch);
	}
}
