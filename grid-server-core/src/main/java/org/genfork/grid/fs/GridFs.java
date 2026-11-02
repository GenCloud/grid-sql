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
package org.genfork.grid.fs;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

import org.genfork.grid.utils.UnsafeMemory;

/**
 * Shared durable filesystem helpers (Unix / Windows).
 * <p>
 * Atomic publish is fail-closed when {@code ATOMIC_MOVE} is unsupported (sealed SoT path).
 * On Windows, {@code REPLACE} sharing violations retry with a bounded backoff (sync FS boundary
 * only — never Netty EL). Does not replace sealed G-00001 {@code closeForOverwrite}.
 * mmap cleanup uses {@code Unsafe.invokeCleaner}; channel close remains the fallback.
 *
 * @author: GenCloud
 * @date: 2025/02
 * @since: 1.0
 */
public final class GridFs {
	private static final String TMP_SUFFIX = ".tmp";
	private static final String MSG_ATOMIC_REQUIRED = "Atomic move is required for durable file ";
	/** Bounded attempts including the first try (Windows REPLACE sharing violation). */
	private static final int WINDOWS_REPLACE_MAX_ATTEMPTS = 16;

	/** Initial park between retries. */
	private static final long WINDOWS_REPLACE_BACKOFF_NANOS = 1_000_000L;

	/** Cap for exponential backoff. */
	private static final long WINDOWS_REPLACE_BACKOFF_MAX_NANOS = 50_000_000L;

	/** Backoff growth factor per failed attempt. */
	private static final int WINDOWS_REPLACE_BACKOFF_FACTOR = 2;

	private GridFs() {
	}

	/**
	 * Create parent directories for {@code dir} (no-op when null).
	 */
	public static Path createDirs(Path dir) throws IOException {
		if (dir == null) {
			return null;
		}
		return Files.createDirectories(dir);
	}

	/**
	 * Create parent directory of {@code file} when present.
	 */
	public static void createParentDirs(Path file) throws IOException {
		if (file == null) {
			return;
		}
		final Path parent = file.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
	}

	/**
	 * Functional writer for atomic publish (writes into sibling {@code .tmp}).
	 */
	@FunctionalInterface
	public interface AtomicWriter {
		void write(Path temporary) throws IOException;
	}

	/**
	 * Write {@code data} to a sibling {@code .tmp} then {@link #moveAtomic(Path, Path)}.
	 */
	public static void writeAtomic(Path target, byte[] data) throws IOException {
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(data, "data");
		writeAtomic(target, temporary -> Files.write(
				temporary,
				data,
				StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE
		));
	}

	/**
	 * Invoke {@code writer} on a sibling {@code .tmp}, then fail-closed {@link #moveAtomic}.
	 */
	public static void writeAtomic(Path target, AtomicWriter writer) throws IOException {
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(writer, "writer");
		final Path temporary = writeUnpublished(target, writer);
		try {
			moveAtomic(temporary, target);
		} catch (IOException | RuntimeException e) {
			deleteQuietly(temporary);
			throw e;
		}
	}

	/**
	 * Write via {@code writer} into a unique unpublished sibling of {@code target} (no publish).
	 * <p>
	 * Used by sealed dump publish-next-then-drop: open/install from the sibling while the
	 * prior generation still maps {@code target}, then {@link #publishFromMapped} after unmap.
	 *
	 * @return durable sibling path the caller must publish or delete
	 */
	public static Path writeUnpublished(Path target, AtomicWriter writer) throws IOException {
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(writer, "writer");
		createParentDirs(target);
		final Path temporary = uniqueSiblingTmp(target);
		try {
			writer.write(temporary);
			return temporary;
		} catch (IOException | RuntimeException e) {
			deleteQuietly(temporary);
			throw e;
		}
	}

	/**
	 * Publish bytes from a (possibly still-mapped) {@code source} onto unmapped {@code target}.
	 * <p>
	 * Reads {@code source} into a fresh unpublished sibling of {@code target}, then
	 * {@link #moveAtomic} — safe when {@code source} remains mmap'd and {@code target} is not.
	 */
	public static void publishFromMapped(Path source, Path target) throws IOException {
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(target, "target");
		writeAtomic(target, temporary -> Files.copy(
				source,
				temporary,
				StandardCopyOption.REPLACE_EXISTING));
	}

	/**
	 * Per-writer unique sibling temp path ({@code file.tmp.<tid>.<nano>}).
	 */
	public static Path uniqueSiblingTmp(Path target) {
		Objects.requireNonNull(target, "target");
		return target.resolveSibling(
				target.getFileName() + TMP_SUFFIX + "." + Thread.currentThread().threadId()
						+ "." + System.nanoTime());
	}

	/**
	 * UTF-8 text write via {@link #writeAtomic(Path, byte[])}.
	 */
	public static void writeAtomic(Path target, String text) throws IOException {
		writeAtomic(target, text, StandardCharsets.UTF_8);
	}

	/**
	 * Text write via {@link #writeAtomic(Path, byte[])}.
	 */
	public static void writeAtomic(Path target, String text, Charset charset) throws IOException {
		Objects.requireNonNull(text, "text");
		Objects.requireNonNull(charset, "charset");
		writeAtomic(target, text.getBytes(charset));
	}

	/**
	 * Fail-closed atomic publish: {@code ATOMIC_MOVE} + {@code REPLACE_EXISTING}.
	 * <p>
	 * On Windows, retries transient {@link AccessDeniedException} with bounded backoff; after the
	 * budget fails closed. {@link AtomicMoveNotSupportedException} always fails closed (no
	 * non-atomic fallback). Sync FS boundary only — not Netty EL.
	 */
	public static void moveAtomic(Path source, Path target) throws IOException {
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(target, "target");
		final boolean windowsRetry = Platform.isWindows();
		final int maxAttempts = windowsRetry ? WINDOWS_REPLACE_MAX_ATTEMPTS : 1;
		long backoffNanos = WINDOWS_REPLACE_BACKOFF_NANOS;
		IOException lastFailure = null;
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			try {
				Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
				return;
			} catch (AtomicMoveNotSupportedException unsupported) {
				throw new IOException(MSG_ATOMIC_REQUIRED + target, unsupported);
			} catch (AccessDeniedException denied) {
				lastFailure = denied;
				if (!windowsRetry || attempt >= maxAttempts) {
					throw denied;
				}
				LockSupport.parkNanos(backoffNanos);
				final long next = backoffNanos * WINDOWS_REPLACE_BACKOFF_FACTOR;
				backoffNanos = Math.min(next, WINDOWS_REPLACE_BACKOFF_MAX_NANOS);
			}
		}
		if (lastFailure != null) {
			throw lastFailure;
		}
		throw new IOException(MSG_ATOMIC_REQUIRED + target);
	}

	/**
	 * Sibling temp path used by atomic writers ({@code file.tmp}).
	 */
	public static Path siblingTmp(Path target) {
		Objects.requireNonNull(target, "target");
		return target.resolveSibling(target.getFileName() + TMP_SUFFIX);
	}

	public static byte[] readAll(Path path) throws IOException {
		return Files.readAllBytes(path);
	}

	public static List<String> readLines(Path path) throws IOException {
		return Files.readAllLines(path, StandardCharsets.UTF_8);
	}

	public static List<String> readLines(Path path, Charset charset) throws IOException {
		return Files.readAllLines(path, charset);
	}

	public static String readString(Path path) throws IOException {
		return Files.readString(path, StandardCharsets.UTF_8);
	}

	public static String readString(Path path, Charset charset) throws IOException {
		return Files.readString(path, charset);
	}

	/**
	 * Direct overwrite (non-atomic). Prefer {@link #writeAtomic} for sealed / SoT files.
	 */
	public static void writeString(Path path, String text) throws IOException {
		writeString(path, text, StandardCharsets.UTF_8);
	}

	public static void writeString(Path path, String text, Charset charset) throws IOException {
		Objects.requireNonNull(path, "path");
		Objects.requireNonNull(text, "text");
		createParentDirs(path);
		Files.writeString(path, text, charset, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE);
	}

	public static void appendString(Path path, String text) throws IOException {
		appendString(path, text, StandardCharsets.UTF_8);
	}

	public static void appendString(Path path, String text, Charset charset) throws IOException {
		Objects.requireNonNull(path, "path");
		Objects.requireNonNull(text, "text");
		createParentDirs(path);
		final StandardOpenOption mode = Files.exists(path)
				? StandardOpenOption.APPEND
				: StandardOpenOption.CREATE;
		Files.writeString(path, text, charset, mode);
	}

	public static boolean deleteIfExists(Path path) throws IOException {
		if (path == null) {
			return false;
		}
		return Files.deleteIfExists(path);
	}

	public static void deleteQuietly(Path path) {
		if (path == null) {
			return;
		}
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
			// best-effort cleanup
		}
	}

	public static boolean exists(Path path) {
		return path != null && Files.exists(path);
	}

	public static boolean isRegularFile(Path path) {
		return path != null && Files.isRegularFile(path);
	}

	public static boolean isDirectory(Path path) {
		return path != null && Files.isDirectory(path);
	}

	public static DirectoryStream<Path> newDirectoryStream(Path dir, String glob) throws IOException {
		return Files.newDirectoryStream(dir, glob);
	}

	/**
	 * Map a whole file read-only (opens and leaves channel to caller via mapping lifetime).
	 */
	public static MappedByteBuffer mapReadOnly(FileChannel channel, long position, long size) throws IOException {
		Objects.requireNonNull(channel, "channel");
		return channel.map(FileChannel.MapMode.READ_ONLY, position, size);
	}

	/**
	 * Release a mapped buffer via {@code Unsafe.invokeCleaner}. Safe on null.
	 * <p>
	 * Hot remap / best-effort close path — failures are swallowed (channel close remains fallback).
	 * Do <strong>not</strong> use before Windows {@code REPLACE} of a still-mapped sealed file;
	 * use {@link #unmapForOverwrite(MappedByteBuffer)} on the overwrite path only.
	 */
	public static void unmap(MappedByteBuffer mapped) {
		if (mapped == null) {
			return;
		}
		try {
			UnsafeMemory.getUnsafe().invokeCleaner(mapped);
		} catch (Throwable ignored) {
			// Channel close remains the fallback when invokeCleaner is unavailable.
		}
	}

	/**
	 * Fail-closed unmap for sealed overwrite (G-00001): cleaner failure surfaces as {@link IOException}.
	 * <p>
	 * Overwrite / {@code closeForOverwrite} only — never hot window remap or best-effort {@code close()}.
	 */
	public static void unmapForOverwrite(MappedByteBuffer mapped) throws IOException {
		if (mapped == null) {
			return;
		}
		try {
			UnsafeMemory.getUnsafe().invokeCleaner(mapped);
		} catch (Throwable failure) {
			throw new IOException("fail-closed unmap before sealed overwrite", failure);
		}
	}
}
