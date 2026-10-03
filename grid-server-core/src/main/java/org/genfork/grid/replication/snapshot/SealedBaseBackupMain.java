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
package org.genfork.grid.replication.snapshot;

import java.nio.file.Path;

/**
 * Offline CLI for PITR sealed base backup / install.
 * <p>
 * Sync I/O on the calling thread — ops only, never Netty EL.
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
public final class SealedBaseBackupMain {
	private static final String ARG_DATA_DIR = "--data-dir";
	private static final String ARG_BACKUP_DIR = "--backup-dir";
	private static final String ARG_WATERMARK = "--watermark";
	private static final String ARG_MODE = "--mode";
	private static final String MODE_BACKUP = "backup";
	private static final String MODE_INSTALL = "install";
	private static final String MODE_CLEAR = "clear";

	private SealedBaseBackupMain() {
	}

	/**
	 * CLI:
	 * {@code --mode backup|install|clear --data-dir DIR [--backup-dir DIR] [--watermark W]}.
	 */
	public static void main(String[] args) {
		String mode = null;
		Path dataDir = null;
		Path backupDir = null;
		long watermark = 0L;
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case ARG_MODE -> mode = args[++i];
				case ARG_DATA_DIR -> dataDir = Path.of(args[++i]);
				case ARG_BACKUP_DIR -> backupDir = Path.of(args[++i]);
				case ARG_WATERMARK -> watermark = Long.parseLong(args[++i]);
				default -> {
				}
			}
		}
		if (mode == null || dataDir == null) {
			System.err.println("Usage: SealedBaseBackupMain --mode backup|install|clear"
					+ " --data-dir DIR [--backup-dir DIR] [--watermark W]");
			System.exit(2);
			return;
		}
		switch (mode) {
			case MODE_BACKUP -> {
				if (backupDir == null) {
					System.err.println("backup requires --backup-dir");
					System.exit(2);
					return;
				}
				final int files = SealedBaseBackupUtil.backupBase(dataDir, backupDir, watermark);
				System.out.println("PITR base backup complete: files=" + files + " watermark=" + watermark);
			}
			case MODE_INSTALL -> {
				if (backupDir == null) {
					System.err.println("install requires --backup-dir");
					System.exit(2);
					return;
				}
				final int files = SealedBaseBackupUtil.installBase(backupDir, dataDir);
				System.out.println("PITR base install complete: files=" + files);
			}
			case MODE_CLEAR -> {
				SealedBaseBackupUtil.clearDataDir(dataDir);
				System.out.println("PITR dataDir cleared: " + dataDir);
			}
			default -> {
				System.err.println("Unknown mode: " + mode);
				System.exit(2);
			}
		}
	}
}