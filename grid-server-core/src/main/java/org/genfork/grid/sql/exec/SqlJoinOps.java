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
package org.genfork.grid.sql.exec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.genfork.grid.catalog.ColumnDef;
import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.query.filters.FilterCondition;
import org.genfork.grid.serial.LogicalFieldCursor;
import org.genfork.grid.serial.WireSpan;
import org.genfork.grid.sql.ast.SelectAst.ColumnSelectItem;
import org.genfork.grid.sql.ast.SelectAst.JoinEdge;
import org.genfork.grid.sql.ast.SelectAst.JoinKind;
import org.genfork.grid.sql.ast.SelectAst.SelectItem;
import org.genfork.grid.sql.ast.SelectAst.SelectSql;
import org.genfork.grid.store.TableStore;

/**
 * Static JOIN helpers (hash / PK probe / projection) shared by {@link SqlQueryExecutor}.
 * <p>
 * Working set is per-side {@code byte[]} blobs ({@link JoinBlobRow}); join keys are
 * {@link WireSpan} from {@link LogicalFieldCursor#indexKeySpan}. {@code Object[]}
 * appears only at the decode / {@link #projectJoined} edge for {@code SqlResult}.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlJoinOps {
	/** Minimum HashMap / HashSet capacity hint for JOIN maps. */
	static final int HASH_MAP_MIN_CAPACITY = 16;

	/** Initial bucket list size for hash JOIN multi-match. */
	private static final int HASH_BUCKET_INITIAL_CAPACITY = 2;

	/** Sentinel: no early LIMIT. */
	private static final int NO_EARLY_LIMIT = 0;

	/** Wire EQ arity for single-column right index probe. */
	private static final int LOOKUP_EQ_WIRE_ARITY = 1;

	/** Same separator as {@link SqlWireAggOps} composite GROUP BY keys. */
	private static final byte COMPOSITE_FIELD_SEP = 0x1F;

	private SqlJoinOps() {
	}

	/**
	 * Wrap committed / snapshot value blobs as single-side {@link JoinBlobRow}s.
	 */
	static List<JoinBlobRow> toJoinRows(List<byte[]> blobs) {
		final List<JoinBlobRow> rows = new ArrayList<>(blobs.size());
		for (byte[] blob : blobs) {
			rows.add(JoinBlobRow.single(blob));
		}
		return rows;
	}

	/**
	 * Multi-side JOIN working row: one value blob per table side ({@code null} = outer pad).
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record JoinBlobRow(byte[][] sides) {
		public JoinBlobRow {
			if (sides == null || sides.length == 0) {
				throw new IllegalArgumentException("join row requires at least one side");
			}
		}

		public static JoinBlobRow single(byte[] blob) {
			return new JoinBlobRow(new byte[][]{blob});
		}
	}

	static List<JoinBlobRow> joinStep(
			List<JoinBlobRow> leftRows,
			List<TableSchema> leftSideSchemas,
			TableStore right,
			List<byte[]> rightBlobs,
			int leftOrd,
			int rightOrd,
			JoinKind kind
	) {
		return joinStep(leftRows, leftSideSchemas, right, rightBlobs,
				new int[]{leftOrd}, new int[]{rightOrd}, kind);
	}

	static List<JoinBlobRow> joinStep(
			List<JoinBlobRow> leftRows,
			List<TableSchema> leftSideSchemas,
			TableStore right,
			List<byte[]> rightBlobs,
			int[] leftOrds,
			int[] rightOrds,
			JoinKind kind
	) {
		final boolean leftOuter = kind == JoinKind.LEFT || kind == JoinKind.FULL;
		final boolean rightOuter = kind == JoinKind.RIGHT || kind == JoinKind.FULL;
		if (leftOrds.length == LOOKUP_EQ_WIRE_ARITY && rightOrds.length == LOOKUP_EQ_WIRE_ARITY) {
			final ColumnDef rightPk = right.schema().pkColumn();
			final boolean rightOnPk = rightPk != null
					&& right.schema().columns().get(rightOrds[0]).name().equalsIgnoreCase(rightPk.name());
			if (rightOnPk && !rightOuter && rightBlobs == null) {
				return joinProbeRightPkStore(leftRows, leftSideSchemas, right, leftOrds[0], leftOuter);
			}
		}

		final List<byte[]> build = rightBlobs == null ? List.of() : rightBlobs;
		return joinHash(leftRows, leftSideSchemas, build, right.schema(),
				leftOrds, rightOrds, leftOuter, rightOuter);
	}

	static String explainJoinLabel(JoinKind kind) {
		return switch (kind) {
			case LEFT -> "LEFT OUTER";
			case RIGHT -> "RIGHT OUTER";
			case FULL -> "FULL OUTER";
			case INNER -> "INNER";
		};
	}

	static List<JoinBlobRow> joinProbeRightPkStore(
			List<JoinBlobRow> leftRows,
			List<TableSchema> leftSideSchemas,
			TableStore right,
			int leftOrd,
			boolean leftOuter
	) {
		return joinProbeRightPkStore(leftRows, leftSideSchemas, right, leftOrd, leftOuter, NO_EARLY_LIMIT);
	}

	/**
	 * Probe right PK from left rows; optional early stop after {@code limitOrZero} outer matches.
	 */
	static List<JoinBlobRow> joinProbeRightPkStore(
			List<JoinBlobRow> leftRows,
			List<TableSchema> leftSideSchemas,
			TableStore right,
			int leftOrd,
			boolean leftOuter,
			int limitOrZero
	) {
		final int capacity = limitOrZero > NO_EARLY_LIMIT
				? Math.min(leftRows.size(), limitOrZero)
				: leftRows.size();
		final List<JoinBlobRow> out = new ArrayList<>(capacity);
		for (JoinBlobRow lr : leftRows) {
			final WireSpan joinKey = wireKeyFromJoined(lr, leftSideSchemas, leftOrd);
			byte[] rr = null;
			if (!isNullWire(joinKey)) {
				rr = right.getCommittedBytes(joinKey.toOwnedBytes());
			}
			if (rr != null) {
				out.add(concatSide(lr, rr));
			} else if (leftOuter) {
				out.add(concatSide(lr, null));
			} else {
				continue;
			}
			if (limitOrZero > NO_EARLY_LIMIT && out.size() >= limitOrZero) {
				break;
			}
		}
		return out;
	}

	static List<JoinBlobRow> joinProbeLeftPk(
			List<byte[]> rightBlobs,
			TableStore left,
			TableSchema rightSchema,
			int rightOrd
	) {
		return joinProbeLeftPk(rightBlobs, left, rightSchema, rightOrd, null, NO_EARLY_LIMIT);
	}

	/**
	 * Probe left PK from right blobs; optional left residual filter and early stop at {@code limitOrZero}.
	 */
	static List<JoinBlobRow> joinProbeLeftPk(
			List<byte[]> rightBlobs,
			TableStore left,
			TableSchema rightSchema,
			int rightOrd,
			FilterCondition leftFilterOrNull,
			int limitOrZero
	) {
		final int capacity = limitOrZero > NO_EARLY_LIMIT
				? Math.min(rightBlobs.size(), limitOrZero)
				: rightBlobs.size();
		final List<JoinBlobRow> out = new ArrayList<>(capacity);
		final TableSchema leftSchema = left.schema();
		for (byte[] rr : rightBlobs) {
			final WireSpan joinKey = wireKeyFromBlob(rr, rightSchema, rightOrd);
			if (isNullWire(joinKey)) {
				continue;
			}
			final byte[] lr = left.getCommittedBytes(joinKey.toOwnedBytes());
			if (lr == null) {
				continue;
			}
			if (leftFilterOrNull != null && !leftFilterOrNull.matches(lr, leftSchema)) {
				continue;
			}
			out.add(new JoinBlobRow(new byte[][]{lr, rr}));
			if (limitOrZero > NO_EARLY_LIMIT && out.size() >= limitOrZero) {
				break;
			}
		}
		return out;
	}

	static List<JoinBlobRow> joinProbeRightIndexStore(
			List<JoinBlobRow> leftRows,
			List<TableSchema> leftSideSchemas,
			TableStore right,
			int leftOrd,
			String rightCol
	) {
		return joinProbeRightIndexStore(
				leftRows, leftSideSchemas, right, new int[]{leftOrd}, List.of(rightCol));
	}

	/**
	 * Probe right covering EQ index from left wire keys (single or composite column list).
	 */
	static List<JoinBlobRow> joinProbeRightIndexStore(
			List<JoinBlobRow> leftRows,
			List<TableSchema> leftSideSchemas,
			TableStore right,
			int[] leftOrds,
			List<String> rightCols
	) {
		if (leftOrds == null || rightCols == null || leftOrds.length != rightCols.size() || leftOrds.length == 0) {
			throw new IllegalArgumentException("join index probe column arity mismatch");
		}
		final byte[][] wireSlot = new byte[leftOrds.length][];
		final Map<WireSpan, List<byte[]>> indexMap = new HashMap<>(
				Math.max(HASH_MAP_MIN_CAPACITY, leftRows.size() * 2));
		for (JoinBlobRow lr : leftRows) {
			final WireSpan mapKey = wireKeyFromJoined(lr, leftSideSchemas, leftOrds);
			if (isNullWire(mapKey) || indexMap.containsKey(mapKey)) {
				continue;
			}
			for (int i = 0; i < leftOrds.length; i++) {
				final WireSpan part = wireKeyFromJoined(lr, leftSideSchemas, leftOrds[i]);
				if (isNullWire(part)) {
					wireSlot[i] = null;
				} else {
					wireSlot[i] = part.toOwnedBytes();
				}
			}
			final List<byte[]> matchKeys = right.lookupEqKeys(rightCols, wireSlot);
			if (matchKeys.isEmpty()) {
				continue;
			}
			final List<byte[]> blobs = new ArrayList<>(matchKeys.size());
			for (byte[] keyBytes : matchKeys) {
				final byte[] value = right.getCommittedBytes(keyBytes);
				if (value != null) {
					blobs.add(value);
				}
			}
			if (!blobs.isEmpty()) {
				indexMap.put(mapKey, blobs);
			}
		}
		final List<JoinBlobRow> out = new ArrayList<>(leftRows.size());
		for (JoinBlobRow lr : leftRows) {
			final List<byte[]> matches = indexMap.get(wireKeyFromJoined(lr, leftSideSchemas, leftOrds));
			if (matches == null || matches.isEmpty()) {
				continue;
			}
			for (byte[] rr : matches) {
				out.add(concatSide(lr, rr));
			}
		}
		return out;
	}


	static List<JoinBlobRow> joinHash(
			List<JoinBlobRow> leftRows,
			List<TableSchema> leftSideSchemas,
			List<byte[]> rightBlobs,
			TableSchema rightSchema,
			int leftOrd,
			int rightOrd,
			boolean leftOuter,
			boolean rightOuter
	) {
		return joinHash(leftRows, leftSideSchemas, rightBlobs, rightSchema,
				new int[]{leftOrd}, new int[]{rightOrd}, leftOuter, rightOuter);
	}

	static List<JoinBlobRow> joinHash(
			List<JoinBlobRow> leftRows,
			List<TableSchema> leftSideSchemas,
			List<byte[]> rightBlobs,
			TableSchema rightSchema,
			int[] leftOrds,
			int[] rightOrds,
			boolean leftOuter,
			boolean rightOuter
	) {
		final Map<WireSpan, List<byte[]>> hash = new HashMap<>(
				Math.max(HASH_MAP_MIN_CAPACITY, rightBlobs.size() * 2));
		for (byte[] row : rightBlobs) {
			hash.computeIfAbsent(
					wireKeyFromBlob(row, rightSchema, rightOrds),
					_ -> new ArrayList<>(HASH_BUCKET_INITIAL_CAPACITY)).add(row);
		}

		final List<JoinBlobRow> out = new ArrayList<>(
				leftRows.size() + (rightOuter ? rightBlobs.size() : 0));
		final HashSet<WireSpan> matchedRightKeys = rightOuter
				? new HashSet<>(Math.max(HASH_MAP_MIN_CAPACITY, rightBlobs.size() * 2))
				: null;
		for (JoinBlobRow lr : leftRows) {
			final WireSpan joinKey = wireKeyFromJoined(lr, leftSideSchemas, leftOrds);
			final List<byte[]> matches = hash.get(joinKey);
			if (matches == null || matches.isEmpty()) {
				if (leftOuter) {
					out.add(concatSide(lr, null));
				}
				continue;
			}

			if (matchedRightKeys != null) {
				matchedRightKeys.add(joinKey);
			}

			for (byte[] rr : matches) {
				out.add(concatSide(lr, rr));
			}
		}

		if (rightOuter) {
			final int leftSideCount = leftSideSchemas.size();
			for (byte[] rr : rightBlobs) {
				final WireSpan joinKey = wireKeyFromBlob(rr, rightSchema, rightOrds);
				if (matchedRightKeys.contains(joinKey)) {
					continue;
				}
				out.add(nullPadLeft(leftSideCount, rr));
			}
		}

		return out;
	}

	/**
	 * Wire join key from a single-table blob field (zero-copy {@link WireSpan}).
	 */
	static WireSpan wireKeyFromBlob(byte[] blob, TableSchema schema, int ordinal) {
		if (blob == null || schema == null) {
			return WireSpan.nullSpan();
		}
		return LogicalFieldCursor.open(schema, blob).indexKeySpan(ordinal);
	}

	static WireSpan wireKeyFromBlob(byte[] blob, TableSchema schema, int[] ordinals) {
		if (ordinals == null || ordinals.length == 0) {
			return WireSpan.nullSpan();
		}
		if (ordinals.length == LOOKUP_EQ_WIRE_ARITY) {
			return wireKeyFromBlob(blob, schema, ordinals[0]);
		}
		return SqlWireAggOps.compositeGroupKeyFromBlob(schema, blob, ordinals).asSpan();
	}

	static WireSpan wireKeyFromJoined(
			JoinBlobRow row,
			List<TableSchema> sideSchemas,
			int globalOrd
	) {
		int offset = 0;
		for (int s = 0; s < sideSchemas.size(); s++) {
			final TableSchema schema = sideSchemas.get(s);
			final int width = schema.columnCount();
			if (globalOrd < offset + width) {
				return wireKeyFromBlob(row.sides()[s], schema, globalOrd - offset);
			}
			offset += width;
		}
		throw new IllegalArgumentException("join ordinal out of range: " + globalOrd);
	}

	static WireSpan wireKeyFromJoined(
			JoinBlobRow row,
			List<TableSchema> sideSchemas,
			int[] globalOrds
	) {
		if (globalOrds == null || globalOrds.length == 0) {
			return WireSpan.nullSpan();
		}
		if (globalOrds.length == LOOKUP_EQ_WIRE_ARITY) {
			return wireKeyFromJoined(row, sideSchemas, globalOrds[0]);
		}
		final byte[][] parts = new byte[globalOrds.length][];
		int total = 0;
		for (int i = 0; i < globalOrds.length; i++) {
			final WireSpan part = wireKeyFromJoined(row, sideSchemas, globalOrds[i]);
			parts[i] = part.toOwnedBytes();
			total += parts[i].length + 1;
		}
		final byte[] composite = new byte[total];
		int pos = 0;
		for (byte[] part : parts) {
			System.arraycopy(part, 0, composite, pos, part.length);
			pos += part.length;
			composite[pos++] = COMPOSITE_FIELD_SEP;
		}
		return WireSpan.ofOwned(composite);
	}


	/**
	 * Decode multi-side blobs to a concatenated {@code Object[]} row (SqlResult edge).
	 */
	static Object[] decodeJoined(JoinBlobRow row, List<TableSchema> sideSchemas) {
		if (row.sides().length != sideSchemas.size()) {
			throw new IllegalArgumentException("side blob/schema count mismatch");
		}
		int total = 0;
		for (TableSchema schema : sideSchemas) {
			total += schema.columnCount();
		}
		final Object[] joined = new Object[total];
		int dest = 0;
		for (int s = 0; s < sideSchemas.size(); s++) {
			final TableSchema schema = sideSchemas.get(s);
			final int width = schema.columnCount();
			final byte[] blob = row.sides()[s];
			if (blob == null) {
				dest += width;
				continue;
			}
			final Object[] projected = LogicalFieldCursor.open(schema, blob).project(null);
			System.arraycopy(projected, 0, joined, dest, width);
			dest += width;
		}
		return joined;
	}

	static List<Object[]> decodeJoinedRows(List<JoinBlobRow> rows, List<TableSchema> sideSchemas) {
		final List<Object[]> out = new ArrayList<>(rows.size());
		for (JoinBlobRow row : rows) {
			out.add(decodeJoined(row, sideSchemas));
		}
		return out;
	}

	static JoinBlobRow concatSide(JoinBlobRow left, byte[] rightBlob) {
		final byte[][] sides = new byte[left.sides().length + 1][];
		System.arraycopy(left.sides(), 0, sides, 0, left.sides().length);
		sides[left.sides().length] = rightBlob;
		return new JoinBlobRow(sides);
	}

	private static JoinBlobRow nullPadLeft(int leftSideCount, byte[] rightBlob) {
		final byte[][] sides = new byte[leftSideCount + 1][];
		sides[leftSideCount] = rightBlob;
		return new JoinBlobRow(sides);
	}

	private static boolean isNullWire(WireSpan wire) {
		return wire == null || wire.isNullWire();
	}

	static List<ColumnDef> concatColumns(List<ColumnDef> left, List<ColumnDef> right) {
		final List<ColumnDef> out = new ArrayList<>(left.size() + right.size());
		out.addAll(left);

		int ord = left.size();
		for (ColumnDef c : right) {
			out.add(new ColumnDef(c.name(), c.type(), c.nullable(), ord++, c.primaryKey(), c.externalOrder()));
		}
		return out;
	}

	static List<String> joinSideTables(SelectSql s) {
		final List<String> sides = new ArrayList<>(1 + (s.joins() == null ? 0 : s.joins().size()));
		sides.add(s.table());
		if (s.joins() != null) {
			for (JoinEdge edge : s.joins()) {
				sides.add(edge.table());
			}
		}
		return sides;
	}

	static Object[] projectJoined(
			List<ColumnDef> cols,
			Object[] row,
			List<String> projection,
			boolean star,
			List<SelectItem> selectItems,
			List<String> sideTables
	) {
		if (star) {
			return row;
		}

		final Object[] projected = new Object[projection.size()];
		for (int i = 0; i < projection.size(); i++) {
			final String col = projection.get(i);
			ColumnSelectItem item = null;
			if (selectItems != null && i < selectItems.size() && selectItems.get(i) instanceof ColumnSelectItem csi) {
				item = csi;
			}
			final int ord = resolveJoinProjectionOrdinal(cols, col, item, sideTables);
			projected[i] = row[ord];
		}

		return projected;
	}

	static int resolveJoinProjectionOrdinal(
			List<ColumnDef> cols,
			String projectionLabel,
			ColumnSelectItem item,
			List<String> sideTables
	) {
		final String simple;
		final String tableQual;
		if (item != null) {
			simple = item.column();
			tableQual = item.tableOrNull();
		} else {
			final int dot = projectionLabel.indexOf('.');
			if (dot > 0) {
				tableQual = projectionLabel.substring(0, dot);
				simple = projectionLabel.substring(dot + 1);
			} else {
				tableQual = null;
				simple = projectionLabel;
			}
		}
		if (tableQual != null && sideTables != null && !sideTables.isEmpty()) {
			int sideIndex = -1;
			for (int s = 0; s < sideTables.size(); s++) {
				final String side = sideTables.get(s);
				if (side.equalsIgnoreCase(tableQual)
						|| side.toLowerCase(Locale.ROOT).endsWith("." + tableQual.toLowerCase(Locale.ROOT))) {
					sideIndex = s;
					break;
				}
			}
			if (sideIndex >= 0) {
				int seen = 0;
				for (int i = 0; i < cols.size(); i++) {
					if (cols.get(i).name().equalsIgnoreCase(simple)) {
						if (seen == sideIndex) {
							return i;
						}
						seen++;
					}
				}
			}
		}
		for (int i = 0; i < cols.size(); i++) {
			if (cols.get(i).name().equalsIgnoreCase(simple)
					|| cols.get(i).name().equalsIgnoreCase(projectionLabel)) {
				return i;
			}
		}
		throw new IllegalArgumentException("Unknown column in projection: " + projectionLabel);
	}
}
