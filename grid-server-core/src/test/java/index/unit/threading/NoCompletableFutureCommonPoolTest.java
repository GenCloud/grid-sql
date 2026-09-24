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
package index.unit.threading;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * CI/source gate: no CompletableFuture.*Async without an explicit Executor
 * (JDK default = ForkJoinPool.commonPool()).
 *
 * @author: GenCloud
 * @date: 2025/02
 * @since: 1.0
 */
class NoCompletableFutureCommonPoolTest {
	private static final Pattern CF_ASYNC_CALL = Pattern.compile(
			"CompletableFuture\\s*\\.\\s*(supplyAsync|runAsync)\\s*\\(");
	private static final Pattern CF_THEN_ASYNC = Pattern.compile(
			"\\.(thenApplyAsync|thenAcceptAsync|thenRunAsync|thenComposeAsync|"
					+ "handleAsync|whenCompleteAsync|exceptionallyAsync)\\s*\\(");
	private static final Pattern COMMON_POOL_CALL = Pattern.compile(
			"ForkJoinPool\\s*\\.\\s*commonPool\\s*\\(\\s*\\)");

	@Test
	void mainSourcesMustNotUseCommonPoolDefaults() throws IOException {
		final Path root = locateServerCoreMain();
		final List<String> violations = new ArrayList<>();
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				if (!file.getFileName().toString().endsWith(".java")) {
					return FileVisitResult.CONTINUE;
				}
				final String text = Files.readString(file, StandardCharsets.UTF_8);
				scanFile(root.relativize(file).toString(), text, violations);
				return FileVisitResult.CONTINUE;
			}
		});
		if (!violations.isEmpty()) {
			fail("ForkJoinPool.commonPool() / CF-*Async without Executor:\n"
					+ String.join("\n", violations)
					+ "\nUse ThreadService / GridFutures with an explicit executor.");
		}
		assertTrue(Files.isDirectory(root));
	}

	private static void scanFile(String rel, String text, List<String> violations) {
		final String stripped = stripComments(text);
		findOneArgAsync(rel, stripped, CF_ASYNC_CALL, violations);
		findOneArgAsync(rel, stripped, CF_THEN_ASYNC, violations);
		final Matcher common = COMMON_POOL_CALL.matcher(stripped);
		while (common.find()) {
			// Allow detection helpers that *inspect* commonPool identity.
			if (rel.replace('\\', '/').contains("threading/CommonPoolGuard.java")) {
				continue;
			}
			violations.add(rel + ": ForkJoinPool.commonPool() at index " + common.start());
		}
	}

	private static void findOneArgAsync(
			String rel,
			String text,
			Pattern pattern,
			List<String> violations
	) {
		final Matcher m = pattern.matcher(text);
		while (m.find()) {
			final int open = m.end() - 1;
			final int close = matchingCloseParen(text, open);
			if (close < 0) {
				continue;
			}
			final String args = text.substring(open + 1, close);
			if (!argsContainsTopLevelComma(args)) {
				violations.add(rel + ": " + m.group() + "...) single-arg -> commonPool");
			}
		}
	}

	private static int matchingCloseParen(String text, int openIdx) {
		int depth = 0;
		boolean inStr = false;
		boolean inChar = false;
		for (int i = openIdx; i < text.length(); i++) {
			final char c = text.charAt(i);
			if (inStr) {
				if (c == '\\') {
					i++;
					continue;
				}
				if (c == '"') {
					inStr = false;
				}
				continue;
			}
			if (inChar) {
				if (c == '\\') {
					i++;
					continue;
				}
				if (c == '\'') {
					inChar = false;
				}
				continue;
			}
			if (c == '"') {
				inStr = true;
				continue;
			}
			if (c == '\'') {
				inChar = true;
				continue;
			}
			if (c == '(') {
				depth++;
			} else if (c == ')') {
				depth--;
				if (depth == 0) {
					return i;
				}
			}
		}
		return -1;
	}

	private static boolean argsContainsTopLevelComma(String args) {
		int depth = 0;
		boolean inStr = false;
		boolean inChar = false;
		for (int i = 0; i < args.length(); i++) {
			final char c = args.charAt(i);
			if (inStr) {
				if (c == '\\') {
					i++;
					continue;
				}
				if (c == '"') {
					inStr = false;
				}
				continue;
			}
			if (inChar) {
				if (c == '\\') {
					i++;
					continue;
				}
				if (c == '\'') {
					inChar = false;
				}
				continue;
			}
			if (c == '"') {
				inStr = true;
				continue;
			}
			if (c == '\'') {
				inChar = true;
				continue;
			}
			if (c == '(' || c == '{' || c == '[') {
				depth++;
			} else if (c == ')' || c == '}' || c == ']') {
				depth--;
			} else if (c == ',' && depth == 0) {
				return true;
			}
		}
		return false;
	}

	private static String stripComments(String src) {
		final StringBuilder sb = new StringBuilder(src.length());
		final int n = src.length();
		int i = 0;
		while (i < n) {
			final char c = src.charAt(i);
			if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
				i += 2;
				while (i < n && src.charAt(i) != '\n') {
					i++;
				}
				continue;
			}
			if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
				i += 2;
				while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
					i++;
				}
				i = Math.min(n, i + 2);
				continue;
			}
			sb.append(c);
			i++;
		}
		return sb.toString();
	}

	private static Path locateServerCoreMain() {
		Path dir = Path.of("").toAbsolutePath();
		for (int i = 0; i < 6; i++) {
			final Path candidate = dir.resolve("src/main/java");
			if (Files.isDirectory(candidate)
					&& Files.isDirectory(candidate.resolve("org/genfork/grid"))) {
				return candidate;
			}
			final Path module = dir.resolve("grid-server-core/src/main/java");
			if (Files.isDirectory(module)) {
				return module;
			}
			dir = dir.getParent();
			if (dir == null) {
				break;
			}
		}
		fail("cannot locate grid-server-core/src/main/java");
		return Path.of(".");
	}
}