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
 * Logger is the class name under {@code org.genfork.grid} so Jepsen
 * {@code LOGGING_LEVEL_ORG_GENFORK_GRID=DEBUG} / {@code logging.level.org.genfork.grid: DEBUG}
 * actually enables it. Evidence unclean-p0: named logger {@code grid.diag.visibility}
 * produced 0 lines while {@code org.genfork.grid} DEBUG flooded — outside that hierarchy.
 * Hot paths call {@link #enabled()} first — no formatting when DEBUG is off.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
public final class VisibilityDiag {
	private static final Logger LOG = LoggerFactory.getLogger(VisibilityDiag.class);

	/**
	 * UPDATE concat/set hit no base row → rowsAffected=0; Jepsen client may INSERT (fork-class).
	 */
	public static final String WHERE_UPDATE_AFFECTED_ZERO = "dml.update.affectedZero";

	/**
	 * PK SELECT returned no committed/dirty value (Elle maps empty row to nil).
	 */
	public static final String WHERE_QUERY_EMPTY_READ = "query.emptyRead";

	/**
	 * Local ORCHID tip advanced without a live contiguous commit broadcast
	 * ({@code advanceCommittedTip} — ship / catch-up). Grep for tip-ok empty-map forks.
	 */
	public static final String WHERE_ORCHID_TIP_ADVANCE = "orchid.tipAdvance";

	/**
	 * Contiguous buffered proposes sealed into tip after proposer loss (unclean / forgetPeer).
	 */
	public static final String WHERE_ORCHID_SEAL_BUFFERED = "orchid.sealBuffered";

	/**
	 * Peer removed from live view; include tip high-water for claim triage.
	 */
	public static final String WHERE_ORCHID_FORGET_PEER = "orchid.forgetPeer";

	/**
	 * writerEligible transition with localTip vs applied-watermark bounds (GHA a2 class).
	 */
	public static final String WHERE_ORCHID_WRITER_ELIGIBLE = "orchid.writerEligible";

	/**
	 * ASYNC ship op arrived while orchid tip already covers opSeq (tip advance skipped).
	 */
	public static final String WHERE_APPLIER_SHIP_TIP_COVERS = "applier.shipTipCovers";

	/**
	 * Sealed/lazy hydrate advanced applied watermark (may not install every key into RAM map).
	 */
	public static final String WHERE_HYDRATE_ADVANCE_APPLIED = "hydrate.advanceApplied";

	private VisibilityDiag() {
	}

	public static boolean enabled() {
		return LOG.isDebugEnabled();
	}

	public static void debug(String where, String detail) {
		if (!enabled()) {
			return;
		}
		LOG.debug("{} thread={} {}", where, Thread.currentThread().getName(), detail);
	}

	public static void debugf(String where, String format, Object... args) {
		if (!enabled()) {
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
