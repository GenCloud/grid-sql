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
package org.genfork.grid.replication.util;

import org.genfork.grid.replication.log.OpLog;

/**
 * Parse and format OpLog {@code domain#shard} stream keys.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class OpLogStreamKeyUtil {
	/** Separator between domain and shard in stream keys. */
	public static final char SEPARATOR = '#';

	private OpLogStreamKeyUtil() {
	}

	/**
	 * Parsed {@code domain#shard} stream key.
	 *
	 * @param domain domain type (left of last {@code #})
	 * @param shard  shard id
	 */
	public record StreamKey(String domain, int shard) {
	}

	/** Format {@code domain#shard}. */
	public static String format(String domain, int shard) {
		return domain + SEPARATOR + shard;
	}

	/**
	 * Parse {@code domain#shard}. Returns {@code null} when the key has no {@code #}
	 * or the shard suffix is not an integer.
	 */
	public static StreamKey parse(String streamKey) {
		if (streamKey == null) {
			return null;
		}
		final int hash = streamKey.lastIndexOf(SEPARATOR);
		if (hash <= 0) {
			return null;
		}
		try {
			final int shard = Integer.parseInt(streamKey.substring(hash + 1));
			return new StreamKey(streamKey.substring(0, hash), shard);
		} catch (NumberFormatException ignored) {
			return null;
		}
	}

	/** True when {@code streamKey} starts with {@code domainType#}. */
	public static boolean startsWithDomain(String streamKey, String domainType) {
		if (streamKey == null || domainType == null) {
			return false;
		}
		return streamKey.startsWith(domainType + SEPARATOR);
	}

	/**
	 * Infer shard count for {@code domainType} from OpLog streams ({@code maxShard + 1}),
	 * else {@code Math.max(1, defaultShardCount)}.
	 */
	public static int shardCountOf(OpLog opLog, String domainType, int defaultShardCount) {
		if (domainType == null) {
			return Math.max(1, defaultShardCount);
		}
		if (opLog != null) {
			int maxShard = -1;
			final String prefix = domainType + SEPARATOR;
			for (String streamKey : opLog.streamKeys()) {
				if (streamKey == null || !streamKey.startsWith(prefix)) {
					continue;
				}
				try {
					final int shard = Integer.parseInt(streamKey.substring(prefix.length()));
					if (shard > maxShard) {
						maxShard = shard;
					}
				} catch (NumberFormatException ignored) {
					// skip malformed
				}
			}
			if (maxShard >= 0) {
				return maxShard + 1;
			}
		}
		return Math.max(1, defaultShardCount);
	}
}
