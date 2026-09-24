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
package org.genfork.grid.replication.tx;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Wire encoding for multi-stream SQL TX envelopes on {@code TX_BEGIN} value bytes.
 * <p>
 * Format (UTF-8): {@code e1|<domain>#<shard>|<domain>#<shard>|...}
 * Single-stream / legacy markers use {@code null}/empty value (no envelope).
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class TxEnvelopeCodec {
	public static final String VERSION_PREFIX = "e1";

	private TxEnvelopeCodec() {
	}

	public record StreamRef(String domain, int shard) {
		public StreamRef {
			Objects.requireNonNull(domain, "domain");
		}

		public String streamKey() {
			return domain + "#" + shard;
		}

		public static StreamRef parse(String token) {
			final int hash = token.lastIndexOf('#');
			if (hash <= 0 || hash >= token.length() - 1) {
				throw new IllegalArgumentException("bad stream token: " + token);
			}
			return new StreamRef(token.substring(0, hash), Integer.parseInt(token.substring(hash + 1)));
		}
	}

	public record Membership(long txId, Set<StreamRef> streams) {
		public Membership {
			Objects.requireNonNull(streams, "streams");
			streams = Set.copyOf(streams);
		}

		public boolean isMultiStream() {
			return streams.size() > 1;
		}
	}

	public static long txIdFromKey(byte[] key) {
		if (key == null || key.length == 0) {
			return 0L;
		}
		final String text = new String(key, StandardCharsets.UTF_8);
		try {
			return Long.parseLong(text, 16);
		} catch (NumberFormatException ex) {
			// Legacy / non-hex marker keys (tests, older ops): stable FNV-1a over bytes.
			long hash = 0xcbf29ce484222325L;
			for (byte b : key) {
				hash ^= (b & 0xff);
				hash *= 0x100000001b3L;
			}
			return hash == 0L ? 1L : hash;
		}
	}

	/**
	 * Encode membership for multi-stream TX; returns {@code null} when ≤1 stream
	 * (caller should leave {@code TX_BEGIN} value null).
	 */
	public static byte[] encode(Collection<StreamRef> streams) {
		if (streams == null || streams.size() <= 1) {
			return null;
		}
		final StringBuilder sb = new StringBuilder(VERSION_PREFIX);
		for (StreamRef s : streams) {
			sb.append('|').append(s.streamKey());
		}
		return sb.toString().getBytes(StandardCharsets.UTF_8);
	}

	/** Decode {@code TX_BEGIN} value; {@code null} when not a multi-stream envelope. */
	public static Membership decode(long txId, byte[] value) {
		if (value == null || value.length == 0) {
			return null;
		}
		final String text = new String(value, StandardCharsets.UTF_8);
		if (!text.startsWith(VERSION_PREFIX + "|") && !text.equals(VERSION_PREFIX)) {
			return null;
		}
		final String[] parts = text.split("\\|", -1);
		if (parts.length < 3 || !VERSION_PREFIX.equals(parts[0])) {
			return null;
		}
		final Set<StreamRef> streams = new LinkedHashSet<>();
		for (int i = 1; i < parts.length; i++) {
			if (parts[i].isEmpty()) {
				continue;
			}
			streams.add(StreamRef.parse(parts[i]));
		}
		if (streams.size() <= 1) {
			return null;
		}
		return new Membership(txId, streams);
	}
}
