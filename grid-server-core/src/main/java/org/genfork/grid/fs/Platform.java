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

import java.util.Locale;

/**
 * OS / filesystem capability hints used by {@link GridFs}.
 *
 * @author: GenCloud
 * @date: 2025/02
 * @since: 1.0
 */
public enum Platform {
	WINDOWS,
	UNIX,
	OTHER;

	private static final String PROP_OS_NAME = "os.name";
	private static final String TOKEN_WIN = "win";
	private static final String TOKEN_MAC = "mac";
	private static final String TOKEN_NIX = "nix";
	private static final String TOKEN_NUX = "nux";
	private static final String TOKEN_AIX = "aix";
	private static final String TOKEN_BSD = "bsd";
	private static final String TOKEN_SUNOS = "sunos";

	private static final Platform DETECTED = detect();

	/**
	 * Cached detection for the current JVM.
	 */
	public static Platform current() {
		return DETECTED;
	}

	/**
	 * Whether this JVM runs on Windows.
	 */
	public static boolean isWindows() {
		return DETECTED == WINDOWS;
	}

	/**
	 * Whether this JVM runs on a Unix-like OS (Linux / macOS / BSD / AIX / Solaris).
	 */
	public static boolean isUnixLike() {
		return DETECTED == UNIX;
	}

	private static Platform detect() {
		final String raw = System.getProperty(PROP_OS_NAME);
		if (raw == null || raw.isBlank()) {
			return OTHER;
		}
		final String name = raw.toLowerCase(Locale.ROOT);
		if (name.contains(TOKEN_WIN)) {
			return WINDOWS;
		}
		if (name.contains(TOKEN_MAC)
				|| name.contains(TOKEN_NIX)
				|| name.contains(TOKEN_NUX)
				|| name.contains(TOKEN_AIX)
				|| name.contains(TOKEN_BSD)
				|| name.contains(TOKEN_SUNOS)) {
			return UNIX;
		}
		return OTHER;
	}
}