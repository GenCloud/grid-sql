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
package org.genfork.grid.replication.pitr;

import java.nio.file.Path;

import org.genfork.grid.context.config.GridConfigurationProperties;

/**
 * Resolve OpLog archive / stream roots from durability props (coordinator wiring only).
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
public final class OpLogArchivePathUtil {
	/** Default archive directory when enabled without an explicit {@code dir}. */
	public static final String DEFAULT_OPLOG_ARCHIVE_DIR = "./data/oplog-archive";

	private OpLogArchivePathUtil() {
	}

	/**
	 * Resolve PITR archive root from durability props; {@code null} when disabled.
	 */
	public static Path resolveArchiveRoot(GridConfigurationProperties.DurabilityProps durability) {
		if (durability == null) {
			return null;
		}
		final GridConfigurationProperties.OpLogArchiveProps archive = durability.getOpLogArchive();
		if (archive == null || !archive.isEnabled()) {
			return null;
		}
		final String dir = archive.getDir();
		if (dir == null || dir.isBlank()) {
			return Path.of(DEFAULT_OPLOG_ARCHIVE_DIR);
		}
		return Path.of(dir);
	}

	/**
	 * Resolve append-only off-node stream root; {@code null} when stream disabled.
	 */
	public static Path resolveStreamRoot(
			GridConfigurationProperties.DurabilityProps durability,
			Path archiveRoot
	) {
		if (durability == null) {
			return null;
		}
		final GridConfigurationProperties.OpLogArchiveProps archive = durability.getOpLogArchive();
		if (archive == null || !archive.isStreamEnabled()) {
			return null;
		}
		final String streamDir = archive.getStreamDir();
		if (streamDir != null && !streamDir.isBlank()) {
			return Path.of(streamDir);
		}
		if (archiveRoot != null) {
			return OpLogArchiveStreamer.defaultStreamRoot(archiveRoot);
		}
		final String dir = archive.getDir();
		final Path base = dir == null || dir.isBlank()
				? Path.of(DEFAULT_OPLOG_ARCHIVE_DIR)
				: Path.of(dir);
		return OpLogArchiveStreamer.defaultStreamRoot(base);
	}
}