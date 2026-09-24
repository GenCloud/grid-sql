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

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

import org.genfork.grid.fs.GridFs;

/**
 * Base backup for PITR: sealed tree + orchid state + locus + index-ckpt at a watermark.
 * <p>
 * Prefers hardlink when the filesystem allows; falls back to copy. Offline / ops path only —
 * not on Netty EL.
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
public final class SealedBaseBackupUtil {
	/** Relative sealed GridMap directory under node dataDir. */
	public static final String SEALED_DIR = "sealed";
	/** Relative ORCHID durable state directory. */
	public static final String ORCHID_DIR = "orchid";
	/** Relative homologous locus directory. */
	public static final String LOCUS_DIR = "locus";
	/** Relative index checkpoint directory. */
	public static final String INDEX_CKPT_DIR = "index-ckpt";
	/** Sidecar watermark note written into the backup root. */
	private static final String WATERMARK_META = "base-watermark.meta";
	private static final String KEY_WATERMARK = "watermark=";
	private static final String KEY_SOURCE = "sourceDataDir=";
	private static final char META_NL = '\n';

	private SealedBaseBackupUtil() {
	}

	/**
	 * Copy or hardlink PITR base artifacts from {@code dataDir} into {@code backupDir}.
	 *
	 * @param dataDir    node data root ({@code …/{cluster}/{node}})
	 * @param backupDir  destination backup root (created if missing)
	 * @param watermark  logical seal / restore watermark for the sidecar meta
	 * @return number of files linked or copied
	 */
	public static int backupBase(Path dataDir, Path backupDir, long watermark) {
		Objects.requireNonNull(dataDir, "dataDir");
		Objects.requireNonNull(backupDir, "backupDir");
		try {
			GridFs.createDirs(backupDir);
			int files = 0;
			files += copyTreeIfPresent(dataDir.resolve(SEALED_DIR), backupDir.resolve(SEALED_DIR));
			files += copyTreeIfPresent(dataDir.resolve(ORCHID_DIR), backupDir.resolve(ORCHID_DIR));
			files += copyTreeIfPresent(dataDir.resolve(LOCUS_DIR), backupDir.resolve(LOCUS_DIR));
			files += copyTreeIfPresent(dataDir.resolve(INDEX_CKPT_DIR), backupDir.resolve(INDEX_CKPT_DIR));
			final String meta = KEY_WATERMARK + watermark + META_NL
					+ KEY_SOURCE + dataDir.toAbsolutePath() + META_NL;
			GridFs.writeAtomic(backupDir.resolve(WATERMARK_META), meta);
			return files;
		} catch (IOException e) {
			throw new IllegalStateException(
					"PITR base backup failed from " + dataDir + " to " + backupDir, e);
		}
	}

	/**
	 * Install a base backup into an empty (or cleared) {@code dataDir}.
	 *
	 * @return number of files installed
	 */
	public static int installBase(Path backupDir, Path dataDir) {
		Objects.requireNonNull(backupDir, "backupDir");
		Objects.requireNonNull(dataDir, "dataDir");
		if (!GridFs.isDirectory(backupDir)) {
			throw new IllegalArgumentException("backupDir is not a directory: " + backupDir);
		}
		try {
			GridFs.createDirs(dataDir);
			int files = 0;
			files += copyTreeIfPresent(backupDir.resolve(SEALED_DIR), dataDir.resolve(SEALED_DIR));
			files += copyTreeIfPresent(backupDir.resolve(ORCHID_DIR), dataDir.resolve(ORCHID_DIR));
			files += copyTreeIfPresent(backupDir.resolve(LOCUS_DIR), dataDir.resolve(LOCUS_DIR));
			files += copyTreeIfPresent(backupDir.resolve(INDEX_CKPT_DIR), dataDir.resolve(INDEX_CKPT_DIR));
			return files;
		} catch (IOException e) {
			throw new IllegalStateException(
					"PITR base install failed from " + backupDir + " to " + dataDir, e);
		}
	}

	/**
	 * Delete all children of {@code dataDir} (creates the dir if missing). Fail-closed on I/O error.
	 */
	public static void clearDataDir(Path dataDir) {
		Objects.requireNonNull(dataDir, "dataDir");
		try {
			if (!GridFs.exists(dataDir)) {
				GridFs.createDirs(dataDir);
				return;
			}
			Files.walkFileTree(dataDir, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					Files.delete(file);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
					if (exc != null) {
						throw exc;
					}
					if (!dir.equals(dataDir)) {
						Files.delete(dir);
					}
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			throw new IllegalStateException("clear dataDir failed: " + dataDir, e);
		}
	}

	private static int copyTreeIfPresent(Path source, Path target) throws IOException {
		if (!GridFs.exists(source)) {
			return 0;
		}
		if (GridFs.isRegularFile(source)) {
			hardlinkOrCopyFile(source, target);
			return 1;
		}
		if (!GridFs.isDirectory(source)) {
			return 0;
		}
		final int[] count = new int[]{0};
		Files.walkFileTree(source, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				final Path rel = source.relativize(dir);
				final Path dest = rel.toString().isEmpty() ? target : target.resolve(rel);
				GridFs.createDirs(dest);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				final Path dest = target.resolve(source.relativize(file));
				hardlinkOrCopyFile(file, dest);
				count[0]++;
				return FileVisitResult.CONTINUE;
			}
		});
		return count[0];
	}

	private static void hardlinkOrCopyFile(Path source, Path target) throws IOException {
		GridFs.createParentDirs(target);
		try {
			Files.createLink(target, source);
		} catch (UnsupportedOperationException | IOException linkFailed) {
			Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
		}
	}
}
