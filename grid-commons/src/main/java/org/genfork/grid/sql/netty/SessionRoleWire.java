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

import org.genfork.grid.nio.EncodeBuffers;
import org.genfork.grid.sql.client.SessionRole;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Encode/decode SESSION_OPEN payload with optional session role (v2) and timezone.
 * <p>
 * Legacy: empty or length-prefixed UTF-8 schema only → {@link SessionRole#PRIMARY}.
 * V2: magic + role + length-prefixed schema [+ optional length-prefixed timezone].
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class SessionRoleWire {
	/** SESSION_OPEN v2 magic (distinct from ASCII schema). */
	public static final byte FORMAT_V2 = (byte) 0xA2;
	private static final int LEN_PREFIX_BYTES = 4;
	private static final String DEFAULT_SCHEMA = "public";
	private static final String DEFAULT_TIMEZONE = "UTC";

	private SessionRoleWire() {
	}

	/**
	 * Encode SESSION_OPEN; always v2 when role is not PRIMARY, schema is non-default,
	 * or timezone is non-default. PRIMARY + public + UTC may use empty legacy payload.
	 */
	public static byte[] encode(SessionRole role, String defaultSchema) {
		return encode(role, defaultSchema, null);
	}

	public static byte[] encode(SessionRole role, String defaultSchema, String timezone) {
		final SessionRole r = role == null ? SessionRole.PRIMARY : role;
		final String schema = normalizeSchema(defaultSchema);
		final String tz = normalizeTimezone(timezone);
		final boolean defaultTz = DEFAULT_TIMEZONE.equalsIgnoreCase(tz);
		if (r == SessionRole.PRIMARY && DEFAULT_SCHEMA.equals(schema) && defaultTz) {
			return new byte[0];
		}
		final byte[] schemaBytes = schema.getBytes(StandardCharsets.UTF_8);
		final byte[] tzBytes = defaultTz ? null : tz.getBytes(StandardCharsets.UTF_8);
		int size = 1 + 1 + LEN_PREFIX_BYTES + schemaBytes.length;
		if (tzBytes != null) {
			size += LEN_PREFIX_BYTES + tzBytes.length;
		}
		final ByteBuffer buf = EncodeBuffers.allocateWireLe(size);
		buf.put(FORMAT_V2);
		buf.put(r.wireCode());
		buf.putInt(schemaBytes.length);
		buf.put(schemaBytes);
		if (tzBytes != null) {
			buf.putInt(tzBytes.length);
			buf.put(tzBytes);
		}
		return EncodeBuffers.toByteArray(buf);
	}

	public static SessionOpen decode(byte[] payload) {
		if (payload == null || payload.length == 0) {
			return new SessionOpen(SessionRole.PRIMARY, DEFAULT_SCHEMA, null);
		}
		if (payload[0] == FORMAT_V2) {
			if (payload.length < 2 + LEN_PREFIX_BYTES) {
				return new SessionOpen(SessionRole.PRIMARY, DEFAULT_SCHEMA, null);
			}
			final ByteBuffer buf = EncodeBuffers.wrapLe(payload);
			buf.get();
			final SessionRole role = SessionRole.fromWire(buf.get());
			final int len = buf.getInt();
			if (len < 0 || buf.remaining() < len) {
				return new SessionOpen(role, DEFAULT_SCHEMA, null);
			}
			final byte[] b = new byte[len];
			buf.get(b);
			final String schema = new String(b, StandardCharsets.UTF_8).trim();
			String timezone = null;
			if (buf.remaining() >= LEN_PREFIX_BYTES) {
				final int tzLen = buf.getInt();
				if (tzLen >= 0 && buf.remaining() >= tzLen) {
					final byte[] tzBytes = new byte[tzLen];
					buf.get(tzBytes);
					final String tz = new String(tzBytes, StandardCharsets.UTF_8).trim();
					if (!tz.isEmpty()) {
						timezone = tz;
					}
				}
			}
			return new SessionOpen(role, schema.isEmpty() ? DEFAULT_SCHEMA : schema, timezone);
		}
		final ByteBuffer buf = EncodeBuffers.wrapLe(payload);
		if (buf.remaining() < LEN_PREFIX_BYTES) {
			return new SessionOpen(SessionRole.PRIMARY, DEFAULT_SCHEMA, null);
		}
		final int len = buf.getInt();
		if (len < 0 || buf.remaining() < len) {
			return new SessionOpen(SessionRole.PRIMARY, DEFAULT_SCHEMA, null);
		}
		final byte[] b = new byte[len];
		buf.get(b);
		final String schema = new String(b, StandardCharsets.UTF_8).trim();
		return new SessionOpen(SessionRole.PRIMARY, schema.isEmpty() ? DEFAULT_SCHEMA : schema, null);
	}

	private static String normalizeSchema(String defaultSchema) {
		if (defaultSchema == null || defaultSchema.isBlank()) {
			return DEFAULT_SCHEMA;
		}
		return defaultSchema.trim();
	}

	private static String normalizeTimezone(String timezone) {
		if (timezone == null || timezone.isBlank()) {
			return DEFAULT_TIMEZONE;
		}
		return timezone.trim();
	}

	/**
	 * Decoded SESSION_OPEN fields.
	 *
	 * @author: GenCloud
	 * @date: 2025/11
	 * @since: 1.0
	 */
	public record SessionOpen(SessionRole role, String schema, String timezoneOrNull) {
		public SessionOpen {
			role = role == null ? SessionRole.PRIMARY : role;
			schema = schema == null || schema.isBlank() ? DEFAULT_SCHEMA : schema;
			timezoneOrNull = timezoneOrNull == null || timezoneOrNull.isBlank() ? null : timezoneOrNull.trim();
		}

		/** Compatibility: role + schema only. */
		public SessionOpen(SessionRole role, String schema) {
			this(role, schema, null);
		}
	}
}