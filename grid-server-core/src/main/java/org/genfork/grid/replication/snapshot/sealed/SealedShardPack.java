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
package org.genfork.grid.replication.snapshot.sealed;

import org.genfork.grid.fs.GridFs;
import org.genfork.grid.replication.durable.ChannelDurableIo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Shared sealed-shard artifact listing and pack/unpack for peer ship/repair.
 * <p>
 * Includes node {@code _n*.gmap} plus sealed BPTree {@code .sbpt}
 * and opt-in BITMAP {@code .sbm} under the same domain/shard prefix — one payload,
 * no second wire protocol.
 *
 * @author: GenCloud
 * @date: 2026/05
 * @since: 1.0
 */
public final class SealedShardPack {
	/** ASCII {@code SSPK}. */
	public static final int MAGIC = 0x5353504B;
	public static final int VERSION = 1;
	/** Magic + version + file-count header before per-file entries. */
	public static final int HEADER_BYTES = Integer.BYTES * 3;
	/** Fixed GMAP header size (must match {@code SealedIntegritySensorUtil.GMAP_FIXED_HEADER_BYTES}). */
	private static final int GMAP_FIXED_HEADER_BYTES = 32;

	private SealedShardPack() {
	}

	/**
	 * {@code true} when {@code packed} is a valid pack header with at least one file entry.
	 */
	public static boolean hasArtifactFiles(byte[] packed) {
		if (packed == null || packed.length < HEADER_BYTES) {
			return false;
		}
		final ByteBuffer buffer = ByteBuffer.wrap(packed);
		if (buffer.getInt() != MAGIC) {
			return false;
		}
		if (buffer.getInt() != VERSION) {
			return false;
		}
		return buffer.getInt() > 0;
	}

	/**
	 * {@code true} when pack has ≥1 {@code .gmap} entry each ≥ fixed header with GMAP magic.
	 * Rejects header-only / empty / truncated-payload packs before ship or apply.
	 */
	public static boolean isCompleteShardArtifacts(byte[] packed) {
		if (!hasArtifactFiles(packed)) {
			return false;
		}
		try {
			int gmapCount = 0;
			for (PackedEntry entry : listPackedEntries(packed)) {
				final String name = entry.name();
				if (!name.endsWith(".gmap")) {
					continue;
				}
				gmapCount++;
				final byte[] content = entry.content();
				if (content.length < GMAP_FIXED_HEADER_BYTES) {
					return false;
				}
				final int magic = ByteBuffer.wrap(content, 0, Integer.BYTES)
						.order(ByteOrder.LITTLE_ENDIAN)
						.getInt();
				if (magic != SealedGridMapWriter.MAGIC) {
					return false;
				}
			}
			return gmapCount > 0;
		} catch (IOException ignored) {
			return false;
		}
	}

	/**
	 * Name+size identity fingerprint of pack entries (cross-host compare; no mtime).
	 */
	public static long identityFingerprintOfPacked(byte[] packed) throws IOException {
		long hash = 0L;
		for (PackedEntry entry : listPackedEntries(packed)) {
			hash = SealedPackFingerprint.mixTokens(hash, entry.name().hashCode());
			hash = SealedPackFingerprint.mixTokens(hash, entry.content().length);
		}
		return hash;
	}

	/**
	 * Entry names present in {@code packed} (relative file names only).
	 */
	public static Set<String> packedEntryNames(byte[] packed) throws IOException {
		final LinkedHashSet<String> names = new LinkedHashSet<>();
		for (PackedEntry entry : listPackedEntries(packed)) {
			names.add(entry.name());
		}
		return Set.copyOf(names);
	}

	/**
	 * Delete local {@code domain#shard} artifacts whose names are not in {@code keepNames}.
	 *
	 * @return number of deleted files
	 */
	public static int purgeOrphansNotIn(
			Path sealedRoot,
			String domain,
			int shard,
			Set<String> keepNames
	) throws IOException {
		Objects.requireNonNull(sealedRoot, "sealedRoot");
		Objects.requireNonNull(domain, "domain");
		Objects.requireNonNull(keepNames, "keepNames");
		int deleted = 0;
		for (Path file : listShardFiles(sealedRoot, domain, shard)) {
			final String name = file.getFileName().toString();
			if (keepNames.contains(name)) {
				continue;
			}
			if (GridFs.isOrphanStagingName(file)) {
				continue;
			}
			if (GridFs.deleteIfExists(file)) {
				deleted++;
			}
		}
		return deleted;
	}

	/** Domain directory prefix shared with {@link SealedGridMapService} / {@link SealedBPTreeService}. */
	public static String domainHex(String domain) {
		return Integer.toHexString(Objects.requireNonNull(domain, "domain").hashCode());
	}

	/**
	 * List on-disk sealed artifacts for {@code domain#shard}:
	 * {@code *.gmap}, then {@code *.sbpt}, then {@code *.sbm}.
	 */
	public static List<Path> listShardFiles(Path sealedRoot, String domain, int shard) throws IOException {
		Objects.requireNonNull(sealedRoot, "sealedRoot");
		Objects.requireNonNull(domain, "domain");
		if (!GridFs.isDirectory(sealedRoot)) {
			return List.of();
		}
		final String hex = domainHex(domain);
		final String prefix = hex + "_" + shard;
		final Set<Path> ordered = new LinkedHashSet<>();
		try (DirectoryStream<Path> nodes = GridFs.newDirectoryStream(sealedRoot, prefix + "_n*.gmap")) {
			final List<Path> nodeFiles = new ArrayList<>();
			for (Path file : nodes) {
				nodeFiles.add(file);
			}
			nodeFiles.sort(Comparator.comparing(p -> p.getFileName().toString()));
			ordered.addAll(nodeFiles);
		}
		try (DirectoryStream<Path> indexes = GridFs.newDirectoryStream(sealedRoot, prefix + "_idx_*.sbpt")) {
			final List<Path> indexFiles = new ArrayList<>();
			for (Path file : indexes) {
				indexFiles.add(file);
			}
			indexFiles.sort(Comparator.comparing(p -> p.getFileName().toString()));
			ordered.addAll(indexFiles);
		}
		try (DirectoryStream<Path> bitmaps = GridFs.newDirectoryStream(sealedRoot, prefix + "_idx_*.sbm")) {
			final List<Path> bitmapFiles = new ArrayList<>();
			for (Path file : bitmaps) {
				bitmapFiles.add(file);
			}
			bitmapFiles.sort(Comparator.comparing(p -> p.getFileName().toString()));
			ordered.addAll(bitmapFiles);
		}
		return List.copyOf(ordered);
	}

	/**
	 * Pack shard sealed files into a single LE blob (magic/version/count + name/len/bytes per file).
	 * Empty artifact list → header-only pack (no file reads).
	 */
	public static byte[] pack(Path sealedRoot, String domain, int shard) throws IOException {
		final List<Path> files = listShardFiles(sealedRoot, domain, shard);
		return packListed(files);
	}

	/**
	 * Pack a pre-listed artifact set (avoids a second directory scan on the migrate hot path).
	 */
	public static byte[] packListed(List<Path> files) throws IOException {
		Objects.requireNonNull(files, "files");
		if (files.isEmpty()) {
			final ByteBuffer empty = ByteBuffer.allocate(HEADER_BYTES);
			empty.putInt(MAGIC);
			empty.putInt(VERSION);
			empty.putInt(0);
			return empty.array();
		}
		int payload = HEADER_BYTES;
		final List<PackedFile> packed = new ArrayList<>(files.size());
		for (Path file : files) {
			final byte[] name = file.getFileName().toString().getBytes(StandardCharsets.UTF_8);
			final byte[] content = GridFs.readAll(file);
			payload = Math.addExact(payload, Math.addExact(8, Math.addExact(name.length, content.length)));
			packed.add(new PackedFile(name, content));
		}
		final ByteBuffer buffer = ByteBuffer.allocate(payload);
		buffer.putInt(MAGIC);
		buffer.putInt(VERSION);
		buffer.putInt(packed.size());
		for (PackedFile file : packed) {
			buffer.putInt(file.name().length);
			buffer.put(file.name());
			buffer.putInt(file.content().length);
			buffer.put(file.content());
		}
		return buffer.array();
	}

	/**
	 * Unpack a {@link #pack} blob into {@code targetRoot}; returns written paths (relative names preserved).
	 * Uses {@link ChannelDurableIo#rewriteFile} so repair/ship share the same durable write helper.
	 */
	public static List<Path> unpack(byte[] packed, Path targetRoot) throws IOException {
		Objects.requireNonNull(targetRoot, "targetRoot");
		final List<PackedEntry> entries = listPackedEntries(packed);
		GridFs.createDirs(targetRoot);
		final List<Path> written = new ArrayList<>(entries.size());
		for (PackedEntry entry : entries) {
			final Path target = targetRoot.resolve(entry.name());
			ChannelDurableIo.rewriteFile(target, entry.content(), false);
			written.add(target);
		}
		return List.copyOf(written);
	}

	private static List<PackedEntry> listPackedEntries(byte[] packed) throws IOException {
		Objects.requireNonNull(packed, "packed");
		if (packed.length < HEADER_BYTES) {
			throw new IOException("sealed pack too short: " + packed.length);
		}
		final ByteBuffer buffer = ByteBuffer.wrap(packed);
		final int magic = buffer.getInt();
		if (magic != MAGIC) {
			throw new IOException("bad sealed pack magic: 0x" + Integer.toHexString(magic));
		}
		final int version = buffer.getInt();
		if (version != VERSION) {
			throw new IOException("unsupported sealed pack version: " + version);
		}
		final int count = buffer.getInt();
		if (count < 0) {
			throw new IOException("negative sealed pack file count: " + count);
		}
		final List<PackedEntry> entries = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			if (buffer.remaining() < 4) {
				throw new IOException("truncated sealed pack name length at file " + i);
			}
			final int nameLen = buffer.getInt();
			if (nameLen <= 0 || nameLen > buffer.remaining()) {
				throw new IOException("invalid sealed pack name length: " + nameLen);
			}
			final byte[] nameBytes = new byte[nameLen];
			buffer.get(nameBytes);
			final String name = new String(nameBytes, StandardCharsets.UTF_8);
			if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.contains("..")) {
				throw new IOException("unsafe sealed pack entry name: " + name);
			}
			if (buffer.remaining() < 4) {
				throw new IOException("truncated sealed pack content length for " + name);
			}
			final int contentLen = buffer.getInt();
			if (contentLen < 0 || contentLen > buffer.remaining()) {
				throw new IOException("invalid sealed pack content length for " + name + ": " + contentLen);
			}
			final byte[] content = new byte[contentLen];
			buffer.get(content);
			entries.add(new PackedEntry(name, content));
		}
		if (buffer.hasRemaining()) {
			throw new IOException("trailing bytes in sealed pack: " + buffer.remaining());
		}
		return entries;
	}

	private record PackedFile(byte[] name, byte[] content) {
	}

	private record PackedEntry(String name, byte[] content) {
	}
}
