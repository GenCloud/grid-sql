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
package org.genfork.grid.replication.durable;

import org.genfork.grid.fs.GridFs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Shared FileChannel write + optional fsync helpers used by orchid state, locus, and similar sidecars.
 *
 * @author: GenCloud
 * @date: 2026/03
 * @since: 1.0
 */
public final class ChannelDurableIo {
	private ChannelDurableIo() {
	}

	public static FileChannel openRw(Path file) throws IOException {
		GridFs.createDirs(file.getParent());
		return FileChannel.open(file,
				StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
	}

	public static void writeAt(FileChannel channel, long position, ByteBuffer buf, boolean fsync) throws IOException {
		channel.position(position);
		while (buf.hasRemaining()) {
			channel.write(buf);
		}
		if (fsync) {
			channel.force(false);
		}
	}

	/** Truncate-rewrite entire file contents. */
	public static void rewriteFile(Path file, ByteBuffer data, boolean fsync) throws IOException {
		try (FileChannel ch = FileChannel.open(file,
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
			while (data.hasRemaining()) {
				ch.write(data);
			}
			if (fsync) {
				ch.force(false);
			}
		}
	}

	public static void rewriteFile(Path file, byte[] data, boolean fsync) throws IOException {
		rewriteFile(file, ByteBuffer.wrap(data), fsync);
	}
}
