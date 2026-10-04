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
package org.genfork.grid.diag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * Server-only SLF4J DEBUG diagnostics for TX / apply / map visibility triage.
 * <p>
 * Enable: {@code logging.level.grid.diag.visibility=DEBUG}
 * (or {@code logging.level.org.genfork.grid.diag.VisibilityDiag=DEBUG}).
 * Hot paths call {@link #enabled()} first — no formatting when DEBUG is off.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
public final class VisibilityDiag {
	private static final Logger LOG = LoggerFactory.getLogger("grid.diag.visibility");

	private VisibilityDiag() {
	}

	public static boolean enabled() {
		return LOG.isDebugEnabled();
	}

	public static void debug(String where, String detail) {
		if (!LOG.isDebugEnabled()) {
			return;
		}
		LOG.debug("{} thread={} {}", where, Thread.currentThread().getName(), detail);
	}

	public static void debugf(String where, String format, Object... args) {
		if (!LOG.isDebugEnabled()) {
			return;
		}
		debug(where, String.format(format, args));
	}

	public static String keyTag(byte[] key) {
		if (key == null) {
			return "key=null";
		}
		return "keyHash=" + Integer.toHexString(Arrays.hashCode(key)) + " keyLen=" + key.length;
	}

	public static String valTag(byte[] value) {
		if (value == null) {
			return "val=null";
		}
		return "valHash=" + Integer.toHexString(Arrays.hashCode(value)) + " valLen=" + value.length;
	}

	public static String preview(Object raw, int maxChars) {
		if (raw == null) {
			return "null";
		}
		final String s = String.valueOf(raw);
		if (s.length() <= maxChars) {
			return s;
		}
		return s.substring(0, maxChars) + "...(len=" + s.length() + ")";
	}
}