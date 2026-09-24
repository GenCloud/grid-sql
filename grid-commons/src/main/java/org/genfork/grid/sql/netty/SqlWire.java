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
package org.genfork.grid.sql.netty;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.netty.buffer.ByteBuf;
import org.genfork.grid.catalog.SqlType;
import org.genfork.grid.catalog.SqlTypeCoercion;
import org.genfork.grid.catalog.SqlTypeWireSizes;
import org.genfork.grid.nio.EncodeBuffers;
import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.client.ServerMeta;
import org.genfork.grid.sql.client.SessionRole;

/**
 * Payload encode/decode helpers for SQL opcodes.
 * EXEC: {@code u32 sessionId | u32 sqlLen | sql | u16 bindCount | cells}.
 * BATCH_EXEC: {@code u32 sessionId | u32 n | n × (u32 sqlLen | utf8 sql)}.
 * <p>
 * WireTiny product path: heap {@link EncodeBuffers#allocateWireLe} + {@code byte[]}.
 * WireLarge / Netty pooled: {@code *Into(ByteBuf)} variants (no {@code toByteArray}).
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SqlWire {
	/** Default initial / subsequent FETCH window (rows). */
	public static final int DEFAULT_FETCH_WINDOW = 64;

	/**
	 * ROW_DESC v2 lead int (not a column count). Classic ROW_DESC starts with {@code count >= 0}.
	 */
	public static final int ROW_DESC_V2_MARKER = -2;

	private static final int LEN_PREFIX_BYTES = Integer.BYTES;
	private static final int SESSION_ID_BYTES = Integer.BYTES;
	private static final int BIND_COUNT_BYTES = Short.BYTES;
	private static final int TYPE_ORDINAL_BYTES = Byte.BYTES;
	private static final int ROW_DESC_FLAGS_BYTES = Byte.BYTES;
	private static final int ROW_DESC_PRECISION_BYTES = Integer.BYTES;
	private static final int EXEC_DONE_AFFECTED_BYTES = Long.BYTES;
	private static final int CELL_TAG_BYTES = Byte.BYTES;
	private static final int SERVER_META_MAGIC = 0x4154454D;
	/** Legacy AUTH_OK / ERROR meta without region epoch. */
	private static final byte SERVER_META_VERSION_V1 = 1;
	/** AUTH_OK / ERROR / PROMOTE_NOTIFY meta with regionEpoch + regionRole. */
	private static final byte SERVER_META_VERSION = 2;
	private static final int SERVER_META_FIXED_BYTES_V1 =
			Integer.BYTES + Byte.BYTES + Byte.BYTES
					+ LEN_PREFIX_BYTES + LEN_PREFIX_BYTES + Long.BYTES;
	private static final int SERVER_META_REGION_TAIL_BYTES = Long.BYTES + Byte.BYTES;
	private static final int SERVER_META_FIXED_BYTES =
			SERVER_META_FIXED_BYTES_V1 + SERVER_META_REGION_TAIL_BYTES;
	private static final int SERVER_META_FLAG_WRITER_ELIGIBLE = 1;
	private static final int SERVER_META_FLAG_APPLY_LAG_STALE = 1 << 1;
	private static final int CELL_INT_BYTES = CELL_TAG_BYTES + Integer.BYTES;
	private static final int CELL_LONG_BYTES = CELL_TAG_BYTES + Long.BYTES;
	private static final int CELL_DOUBLE_BYTES = CELL_TAG_BYTES + Double.BYTES;
	private static final int CELL_STRING_HEADER_BYTES = CELL_TAG_BYTES + LEN_PREFIX_BYTES;

	private static final int ROW_DESC_FLAG_NULLABLE_KNOWN = 1;
	private static final int ROW_DESC_FLAG_NULLABLE_TRUE = 1 << 1;

	private static final byte CELL_NULL = 0;
	private static final byte CELL_INT = 1;
	private static final byte CELL_LONG = 2;
	private static final byte CELL_DOUBLE = 3;
	private static final byte CELL_BOOL = 4;
	private static final byte CELL_STRING = 5;
	private static final byte CELL_UUID = 6;
	private static final byte CELL_DATE = 7;
	private static final byte CELL_TIME = 8;
	private static final byte CELL_INSTANT = 9;

	private static final int CELL_UUID_BYTES = CELL_TAG_BYTES + 16;
	private static final int CELL_DATE_BYTES = CELL_TAG_BYTES + Integer.BYTES;
	private static final int CELL_TIME_BYTES = CELL_TAG_BYTES + Long.BYTES;
	private static final int CELL_INSTANT_BYTES = CELL_TAG_BYTES + Long.BYTES + Integer.BYTES;

	private SqlWire() {
	}

	public static byte[] utf8(String s) {
		final byte[] b = s.getBytes(StandardCharsets.UTF_8);
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(LEN_PREFIX_BYTES + b.length);
		buf.putInt(b.length);
		buf.put(b);
		return EncodeBuffers.toByteArray(buf);
	}

	/** Length-prefixed UTF-8 into {@code out} (same LE layout as {@link #utf8(String)}). */
	public static void utf8Into(ByteBuf out, String s) {
		lengthPrefixedBytesInto(out, s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8));
	}

	/** LE u32 length + raw bytes (shared length-prefix helper for encodeInto paths). */
	public static void lengthPrefixedBytesInto(ByteBuf out, byte[] bytes) {
		final byte[] b = bytes == null ? new byte[0] : bytes;
		out.writeIntLE(b.length);
		out.writeBytes(b);
	}

	/** EXEC payload with multiplex session id. */
	public static byte[] exec(int sessionId, String sql, Object[] binds) {
		final byte[] sqlBytes = sql.getBytes(StandardCharsets.UTF_8);
		final Object[] b = binds == null ? new Object[0] : binds;
		final List<byte[]> cells = new ArrayList<>(b.length);
		int cellBytes = 0;
		for (Object cell : b) {
			final byte[] e = encodeCell(cell);
			cells.add(e);
			cellBytes += e.length;
		}
		final int size = SESSION_ID_BYTES + LEN_PREFIX_BYTES + sqlBytes.length + BIND_COUNT_BYTES + cellBytes;
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(size);
		buf.putInt(sessionId);
		buf.putInt(sqlBytes.length);
		buf.put(sqlBytes);
		buf.putShort((short) b.length);
		for (byte[] e : cells) {
			buf.put(e);
		}
		return EncodeBuffers.toByteArray(buf);
	}

	/**
	 * EXEC payload written directly into {@code out} (same LE layout as {@link #exec}).
	 * Avoids intermediate {@code byte[]} / {@link EncodeBuffers#toByteArray}.
	 */
	public static void execInto(ByteBuf out, int sessionId, String sql, Object[] binds) {
		final byte[] sqlBytes = (sql == null ? "" : sql).getBytes(StandardCharsets.UTF_8);
		final Object[] b = binds == null ? new Object[0] : binds;
		out.writeIntLE(sessionId);
		out.writeIntLE(sqlBytes.length);
		out.writeBytes(sqlBytes);
		out.writeShortLE(b.length);
		for (Object cell : b) {
			encodeCellInto(out, cell);
		}
	}

	public record ExecPayload(int sessionId, String sql, Object[] binds) {
	}

	public static ExecPayload readExec(byte[] payload) {
		final ByteBuffer buf = EncodeBuffers.wrapLe(payload);
		final int sessionId = buf.getInt();
		final String sql = readUtf8(buf);
		if (!buf.hasRemaining()) {
			return new ExecPayload(sessionId, sql, new Object[0]);
		}
		final int count = buf.getShort() & 0xffff;
		final Object[] binds = new Object[count];
		for (int i = 0; i < count; i++) {
			binds[i] = decodeCell(buf);
		}
		return new ExecPayload(sessionId, sql, binds);
	}

	/**
	 * BATCH_EXEC payload: session id + ordered UTF-8 SQL strings (no binds).
	 */
	public static byte[] batchExec(int sessionId, List<String> sqls) {
		final List<String> list = sqls == null ? List.of() : sqls;
		int size = SESSION_ID_BYTES + LEN_PREFIX_BYTES;
		final List<byte[]> encoded = new ArrayList<>(list.size());
		for (String sql : list) {
			final byte[] b = (sql == null ? "" : sql).getBytes(StandardCharsets.UTF_8);
			encoded.add(b);
			size += LEN_PREFIX_BYTES + b.length;
		}
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(size);
		buf.putInt(sessionId);
		buf.putInt(encoded.size());
		for (byte[] b : encoded) {
			buf.putInt(b.length);
			buf.put(b);
		}
		return EncodeBuffers.toByteArray(buf);
	}

	/**
	 * BATCH_EXEC payload into {@code out} (same LE layout as {@link #batchExec}).
	 */
	public static void batchExecInto(ByteBuf out, int sessionId, List<String> sqls) {
		final List<String> list = sqls == null ? List.of() : sqls;
		out.writeIntLE(sessionId);
		out.writeIntLE(list.size());
		for (String sql : list) {
			lengthPrefixedBytesInto(out, (sql == null ? "" : sql).getBytes(StandardCharsets.UTF_8));
		}
	}

	public record BatchExecPayload(int sessionId, List<String> sqls) {
	}

	public static BatchExecPayload readBatchExec(byte[] payload) {
		final ByteBuffer buf = EncodeBuffers.wrapLe(payload);
		final int sessionId = buf.getInt();
		final int n = buf.getInt();
		if (n < 0) {
			throw new IllegalArgumentException("BATCH_EXEC n < 0");
		}
		final List<String> sqls = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			sqls.add(readUtf8(buf));
		}
		return new BatchExecPayload(sessionId, List.copyOf(sqls));
	}

	public static byte[] sessionId(int id) {
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(SESSION_ID_BYTES);
		buf.putInt(id);
		return EncodeBuffers.toByteArray(buf);
	}

	public static int readSessionId(byte[] payload) {
		return EncodeBuffers.wrapLe(payload).getInt();
	}

	/** FETCH payload: LE u32 n (row demand). */
	public static byte[] fetch(int n) {
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(LEN_PREFIX_BYTES);
		buf.putInt(Math.max(0, n));
		return EncodeBuffers.toByteArray(buf);
	}

	public static int readFetch(byte[] payload) {
		if (payload == null || payload.length < LEN_PREFIX_BYTES) {
			return 0;
		}
		return EncodeBuffers.wrapLe(payload).getInt();
	}

	/** CANCEL payload (empty). */
	public static byte[] cancel() {
		return new byte[0];
	}

	/**
	 * SESSION_OPEN payload: optional default schema (utf8 length-prefixed).
	 * Empty / null schema → server keeps {@code public}.
	 */
	public static byte[] sessionOpen(String defaultSchema) {
		return sessionOpen(SessionRole.PRIMARY, defaultSchema);
	}

	/** SESSION_OPEN with role (v2 wire when non-default). */
	public static byte[] sessionOpen(SessionRole role, String defaultSchema) {
		return SessionRoleWire.encode(role, defaultSchema);
	}

	/** SESSION_OPEN with role + optional session timezone (IANA id). */
	public static byte[] sessionOpen(SessionRole role, String defaultSchema, String timezone) {
		return SessionRoleWire.encode(role, defaultSchema, timezone);
	}

	/** Read SESSION_OPEN schema; empty payload → {@code public}. */
	public static String readSessionOpenSchema(byte[] payload) {
		return SessionRoleWire.decode(payload).schema();
	}

	/** Decode SESSION_OPEN role + schema. */
	public static SessionRoleWire.SessionOpen readSessionOpen(byte[] payload) {
		return SessionRoleWire.decode(payload);
	}

	public static String readUtf8(ByteBuffer buf) {
		final int len = buf.getInt();
		final byte[] b = new byte[len];
		buf.get(b);
		return new String(b, StandardCharsets.UTF_8);
	}

	public static byte[] auth(String user, String password) {
		final byte[] u = user == null ? new byte[0] : user.getBytes(StandardCharsets.UTF_8);
		final byte[] p = password == null ? new byte[0] : password.getBytes(StandardCharsets.UTF_8);
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(
				LEN_PREFIX_BYTES + u.length + LEN_PREFIX_BYTES + p.length);
		buf.putInt(u.length);
		buf.put(u);
		buf.putInt(p.length);
		buf.put(p);
		return EncodeBuffers.toByteArray(buf);
	}

	public static String[] readAuth(byte[] payload) {
		final ByteBuffer buf = EncodeBuffers.wrapLe(payload);
		return new String[]{readUtf8(buf), readUtf8(buf)};
	}

	/**
	 * Encode AUTH_OK / ERROR / {@link SqlOpcode#PROMOTE_NOTIFY} proposer metadata.
	 */
	public static byte[] serverMeta(ServerMeta meta) {
		final ServerMeta value = meta == null ? ServerMeta.EMPTY : meta;
		final byte[] nodeId = utf8Bytes(value.nodeId());
		final byte[] promoteHint = utf8Bytes(value.promoteHint());
		final int size = SERVER_META_FIXED_BYTES + nodeId.length + promoteHint.length;
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(size);
		buf.putInt(SERVER_META_MAGIC);
		buf.put(SERVER_META_VERSION);
		buf.put(serverMetaFlags(value));
		putLengthPrefixed(buf, nodeId);
		putLengthPrefixed(buf, promoteHint);
		buf.putLong(value.schemaEpoch());
		buf.putLong(value.regionEpoch());
		buf.put(value.regionRole());
		return EncodeBuffers.toByteArray(buf);
	}

	/**
	 * Decode AUTH_OK / ERROR / {@link SqlOpcode#PROMOTE_NOTIFY} proposer metadata. Empty payload
	 * is accepted for compatibility with servers predating ServerMeta. Version 1 payloads omit
	 * region fields (decoded as zero / {@link ServerMeta#REGION_ROLE_NONE}).
	 */
	public static ServerMeta readServerMeta(byte[] payload) {
		if (payload == null || payload.length == 0) {
			return ServerMeta.EMPTY;
		}
		return readServerMeta(EncodeBuffers.wrapLe(payload));
	}

	private static ServerMeta readServerMeta(ByteBuffer buf) {
		if (buf.remaining() < SERVER_META_FIXED_BYTES_V1) {
			return ServerMeta.EMPTY;
		}
		final int magic = buf.getInt();
		if (magic != SERVER_META_MAGIC) {
			return ServerMeta.EMPTY;
		}
		final byte version = buf.get();
		if (version != SERVER_META_VERSION && version != SERVER_META_VERSION_V1) {
			return ServerMeta.EMPTY;
		}
		final int flags = buf.get() & 0xff;
		final String nodeId = readUtf8(buf);
		final String promoteHint = readUtf8(buf);
		final long schemaEpoch = buf.getLong();
		long regionEpoch = 0L;
		byte regionRole = ServerMeta.REGION_ROLE_NONE;
		if (version == SERVER_META_VERSION && buf.remaining() >= SERVER_META_REGION_TAIL_BYTES) {
			regionEpoch = buf.getLong();
			regionRole = buf.get();
		}
		return new ServerMeta(
				nodeId,
				(flags & SERVER_META_FLAG_WRITER_ELIGIBLE) != 0,
				promoteHint,
				(flags & SERVER_META_FLAG_APPLY_LAG_STALE) != 0,
				schemaEpoch,
				regionEpoch,
				regionRole);
	}

	public static byte[] error(int code, String message) {
		return error(code, message, null);
	}

	/**
	 * Encode ERROR with an optional ServerMeta tail.
	 */
	public static byte[] error(int code, String message, ServerMeta meta) {
		final byte[] m = message.getBytes(StandardCharsets.UTF_8);
		final byte[] encodedMeta = meta == null ? new byte[0] : serverMeta(meta);
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(
				Integer.BYTES + LEN_PREFIX_BYTES + m.length + encodedMeta.length);
		buf.putInt(code);
		buf.putInt(m.length);
		buf.put(m);
		buf.put(encodedMeta);
		return EncodeBuffers.toByteArray(buf);
	}

	/**
	 * Decode ERROR and its optional ServerMeta tail.
	 */
	public static ErrorPayload readError(byte[] payload) {
		final ByteBuffer buf = EncodeBuffers.wrapLe(payload);
		final int code = buf.getInt();
		final String message = readUtf8(buf);
		final boolean hasServerMeta = buf.hasRemaining();
		final ServerMeta meta = hasServerMeta ? readServerMeta(buf) : ServerMeta.EMPTY;
		return new ErrorPayload(code, message, meta, hasServerMeta);
	}

	/**
	 * Decoded SQL ERROR payload.
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record ErrorPayload(int code, String message, ServerMeta serverMeta, boolean hasServerMeta) {
	}

	/**
	 * ROW_DESC v2: marker, count, then per column name / type / flags / precision / schema / table.
	 */
	public static byte[] rowDesc(List<SqlResult.ColumnMeta> columns) {
		final List<byte[]> names = new ArrayList<>(columns.size());
		final List<byte[]> schemas = new ArrayList<>(columns.size());
		final List<byte[]> tables = new ArrayList<>(columns.size());
		int size = LEN_PREFIX_BYTES + LEN_PREFIX_BYTES;
		for (SqlResult.ColumnMeta c : columns) {
			final byte[] name = utf8Bytes(c.name());
			final byte[] schema = utf8Bytes(c.schemaName());
			final byte[] table = utf8Bytes(c.tableName());
			names.add(name);
			schemas.add(schema);
			tables.add(table);
			size += LEN_PREFIX_BYTES + name.length
					+ TYPE_ORDINAL_BYTES
					+ ROW_DESC_FLAGS_BYTES
					+ ROW_DESC_PRECISION_BYTES
					+ LEN_PREFIX_BYTES + schema.length
					+ LEN_PREFIX_BYTES + table.length;
		}
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(size);
		buf.putInt(ROW_DESC_V2_MARKER);
		buf.putInt(columns.size());
		for (int i = 0; i < columns.size(); i++) {
			final SqlResult.ColumnMeta c = columns.get(i);
			putLengthPrefixed(buf, names.get(i));
			buf.put((byte) c.type().ordinal());
			buf.put(rowDescFlags(c));
			buf.putInt(c.precision());
			putLengthPrefixed(buf, schemas.get(i));
			putLengthPrefixed(buf, tables.get(i));
		}
		return EncodeBuffers.toByteArray(buf);
	}

	/** Same layout as {@link #rowDesc} into pooled {@link ByteBuf} (WireLarge / Netty). */
	public static void rowDescInto(ByteBuf out, List<SqlResult.ColumnMeta> columns) {
		out.writeIntLE(ROW_DESC_V2_MARKER);
		out.writeIntLE(columns.size());
		for (SqlResult.ColumnMeta c : columns) {
			lengthPrefixedBytesInto(out, utf8Bytes(c.name()));
			out.writeByte((byte) c.type().ordinal());
			out.writeByte(rowDescFlags(c));
			out.writeIntLE(c.precision());
			lengthPrefixedBytesInto(out, utf8Bytes(c.schemaName()));
			lengthPrefixedBytesInto(out, utf8Bytes(c.tableName()));
		}
	}

	public static byte[] rowData(Object[] cells) {
		final List<byte[]> encoded = new ArrayList<>(cells.length);
		int size = LEN_PREFIX_BYTES;
		for (Object cell : cells) {
			final byte[] e = encodeCell(cell);
			encoded.add(e);
			size += e.length;
		}
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(size);
		buf.putInt(cells.length);
		for (byte[] e : encoded) {
			buf.put(e);
		}
		return EncodeBuffers.toByteArray(buf);
	}

	/** Same layout as {@link #rowData} into pooled {@link ByteBuf} (WireLarge / Netty). */
	public static void rowDataInto(ByteBuf out, Object[] cells) {
		final Object[] c = cells == null ? new Object[0] : cells;
		out.writeIntLE(c.length);
		for (Object cell : c) {
			encodeCellInto(out, cell);
		}
	}

	public static byte[] execDone(long rowsAffected, String tag) {
		final byte[] t = tag.getBytes(StandardCharsets.UTF_8);
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(
				EXEC_DONE_AFFECTED_BYTES + LEN_PREFIX_BYTES + t.length);
		buf.putLong(rowsAffected);
		buf.putInt(t.length);
		buf.put(t);
		return EncodeBuffers.toByteArray(buf);
	}

	private static byte[] encodeCell(Object cell) {
		if (cell == null) {
			return new byte[]{CELL_NULL};
		}
		if (cell instanceof Integer i) {
			final ByteBuffer b = EncodeBuffers.allocateWireLe(CELL_INT_BYTES);
			b.put(CELL_INT);
			b.putInt(i);
			return EncodeBuffers.toByteArray(b);
		}
		if (cell instanceof Long l) {
			final ByteBuffer b = EncodeBuffers.allocateWireLe(CELL_LONG_BYTES);
			b.put(CELL_LONG);
			b.putLong(l);
			return EncodeBuffers.toByteArray(b);
		}
		if (cell instanceof Double d) {
			final ByteBuffer b = EncodeBuffers.allocateWireLe(CELL_DOUBLE_BYTES);
			b.put(CELL_DOUBLE);
			b.putDouble(d);
			return EncodeBuffers.toByteArray(b);
		}
		if (cell instanceof Boolean bo) {
			return new byte[]{CELL_BOOL, (byte) (bo ? 1 : 0)};
		}
		if (cell instanceof UUID u) {
			final byte[] raw = SqlTypeCoercion.uuidToBytes(u);
			final ByteBuffer b = EncodeBuffers.allocateWireLe(CELL_UUID_BYTES);
			b.put(CELL_UUID);
			b.put(raw);
			return EncodeBuffers.toByteArray(b);
		}
		if (cell instanceof LocalDate d) {
			final ByteBuffer b = EncodeBuffers.allocateWireLe(CELL_DATE_BYTES);
			b.put(CELL_DATE);
			b.putInt((int) d.toEpochDay());
			return EncodeBuffers.toByteArray(b);
		}
		if (cell instanceof LocalTime t) {
			final ByteBuffer b = EncodeBuffers.allocateWireLe(CELL_TIME_BYTES);
			b.put(CELL_TIME);
			b.putLong(t.toNanoOfDay());
			return EncodeBuffers.toByteArray(b);
		}
		if (cell instanceof Instant i) {
			final ByteBuffer b = EncodeBuffers.allocateWireLe(CELL_INSTANT_BYTES);
			b.put(CELL_INSTANT);
			b.putLong(i.getEpochSecond());
			b.putInt(i.getNano());
			return EncodeBuffers.toByteArray(b);
		}
		final byte[] s = String.valueOf(cell).getBytes(StandardCharsets.UTF_8);
		final ByteBuffer b = EncodeBuffers.allocateWireLe(CELL_STRING_HEADER_BYTES + s.length);
		b.put(CELL_STRING);
		b.putInt(s.length);
		b.put(s);
		return EncodeBuffers.toByteArray(b);
	}

	/** Same cell tags/layout as {@link #encodeCell} written into {@code out}. */
	private static void encodeCellInto(ByteBuf out, Object cell) {
		if (cell == null) {
			out.writeByte(CELL_NULL);
			return;
		}
		if (cell instanceof Integer i) {
			out.writeByte(CELL_INT);
			out.writeIntLE(i);
			return;
		}
		if (cell instanceof Long l) {
			out.writeByte(CELL_LONG);
			out.writeLongLE(l);
			return;
		}
		if (cell instanceof Double d) {
			out.writeByte(CELL_DOUBLE);
			out.writeDoubleLE(d.doubleValue());
			return;
		}
		if (cell instanceof Boolean bo) {
			out.writeByte(CELL_BOOL);
			out.writeByte(bo ? 1 : 0);
			return;
		}
		if (cell instanceof UUID u) {
			out.writeByte(CELL_UUID);
			out.writeBytes(SqlTypeCoercion.uuidToBytes(u));
			return;
		}
		if (cell instanceof LocalDate d) {
			out.writeByte(CELL_DATE);
			out.writeIntLE((int) d.toEpochDay());
			return;
		}
		if (cell instanceof LocalTime t) {
			out.writeByte(CELL_TIME);
			out.writeLongLE(t.toNanoOfDay());
			return;
		}
		if (cell instanceof Instant i) {
			out.writeByte(CELL_INSTANT);
			out.writeLongLE(i.getEpochSecond());
			out.writeIntLE(i.getNano());
			return;
		}
		final byte[] s = String.valueOf(cell).getBytes(StandardCharsets.UTF_8);
		out.writeByte(CELL_STRING);
		out.writeIntLE(s.length);
		out.writeBytes(s);
	}

	public static Object decodeCell(ByteBuffer buf) {
		final byte kind = buf.get();
		return switch (kind) {
			case CELL_NULL -> null;
			case CELL_INT -> buf.getInt();
			case CELL_LONG -> buf.getLong();
			case CELL_DOUBLE -> buf.getDouble();
			case CELL_BOOL -> buf.get() != 0;
			case CELL_STRING -> {
				final int len = buf.getInt();
				final byte[] s = new byte[len];
				buf.get(s);
				yield new String(s, StandardCharsets.UTF_8);
			}
			case CELL_UUID -> {
				final byte[] raw = new byte[SqlTypeWireSizes.UUID_BYTES];
				buf.get(raw);
				yield SqlTypeCoercion.uuidFromBytes(raw);
			}
			case CELL_DATE -> LocalDate.ofEpochDay(buf.getInt());
			case CELL_TIME -> LocalTime.ofNanoOfDay(buf.getLong());
			case CELL_INSTANT -> Instant.ofEpochSecond(buf.getLong(), buf.getInt());
			default -> throw new IllegalArgumentException("bad cell kind " + kind);
		};
	}

	public static List<SqlResult.ColumnMeta> decodeRowDesc(byte[] payload) {
		final ByteBuffer buf = EncodeBuffers.wrapLe(payload);
		final int first = buf.getInt();
		final SqlType[] types = SqlType.values();
		if (first == ROW_DESC_V2_MARKER) {
			final int n = buf.getInt();
			final List<SqlResult.ColumnMeta> out = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				final String name = readUtf8(buf);
				final int ord = buf.get() & 0xff;
				final int flags = buf.get() & 0xff;
				final int precision = buf.getInt();
				final String schema = readUtf8(buf);
				final String table = readUtf8(buf);
				final Boolean nullable;
				if ((flags & ROW_DESC_FLAG_NULLABLE_KNOWN) != 0) {
					nullable = Boolean.valueOf((flags & ROW_DESC_FLAG_NULLABLE_TRUE) != 0);
				} else {
					nullable = null;
				}
				out.add(new SqlResult.ColumnMeta(
						name,
						types[ord],
						nullable,
						Integer.valueOf(precision),
						emptyToNull(table),
						emptyToNull(schema)));
			}
			return out;
		}
		final int n = first;
		final List<SqlResult.ColumnMeta> out = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			final String name = readUtf8(buf);
			final int ord = buf.get() & 0xff;
			out.add(SqlResult.ColumnMeta.of(name, types[ord]));
		}
		return out;
	}

	private static byte rowDescFlags(SqlResult.ColumnMeta c) {
		int flags = 0;
		if (c.nullableKnown()) {
			flags |= ROW_DESC_FLAG_NULLABLE_KNOWN;
			if (c.nullable()) {
				flags |= ROW_DESC_FLAG_NULLABLE_TRUE;
			}
		}
		return (byte) flags;
	}

	private static byte serverMetaFlags(ServerMeta meta) {
		int flags = 0;
		if (meta.writerEligible()) {
			flags |= SERVER_META_FLAG_WRITER_ELIGIBLE;
		}
		if (meta.applyLagStale()) {
			flags |= SERVER_META_FLAG_APPLY_LAG_STALE;
		}
		return (byte) flags;
	}

	private static byte[] utf8Bytes(String s) {
		return (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
	}

	private static void putLengthPrefixed(ByteBuffer buf, byte[] bytes) {
		buf.putInt(bytes.length);
		buf.put(bytes);
	}

	private static String emptyToNull(String s) {
		return s == null || s.isEmpty() ? null : s;
	}

	public static Object[] decodeRowData(byte[] payload) {
		final ByteBuffer buf = EncodeBuffers.wrapLe(payload);
		final int n = buf.getInt();
		final Object[] cells = new Object[n];
		for (int i = 0; i < n; i++) {
			cells[i] = decodeCell(buf);
		}
		return cells;
	}
}