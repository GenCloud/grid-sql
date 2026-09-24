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
package org.genfork.grid.threading;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * CompletableFuture helpers that never use ForkJoinPool.commonPool().
 *
 * @author: GenCloud
 * @date: 2025/02
 * @since: 1.0
 */
public final class GridFutures {
	private GridFutures() {
	}

	public static <T> CompletableFuture<T> supplyLogic(Supplier<T> supplier) {
		return CompletableFuture.supplyAsync(supplier, ThreadService.getLogicExecutor());
	}

	public static CompletableFuture<Void> runLogic(Runnable runnable) {
		return CompletableFuture.runAsync(runnable, ThreadService.getLogicExecutor());
	}

	public static <T> CompletableFuture<T> supplyCpu(Supplier<T> supplier) {
		return CompletableFuture.supplyAsync(supplier, ThreadService.getCpuExecutor());
	}

	public static CompletableFuture<Void> runCpu(Runnable runnable) {
		return CompletableFuture.runAsync(runnable, ThreadService.getCpuExecutor());
	}

	public static <T> CompletableFuture<T> supplyNetwork(Supplier<T> supplier) {
		return CompletableFuture.supplyAsync(supplier, ThreadService.getNetworkExecutor());
	}

	public static CompletableFuture<Void> runNetwork(Runnable runnable) {
		return CompletableFuture.runAsync(runnable, ThreadService.getNetworkExecutor());
	}

	public static <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier, Executor executor) {
		if (executor == null) {
			throw new IllegalArgumentException("executor required (never commonPool default)");
		}
		return CompletableFuture.supplyAsync(supplier, executor);
	}

	public static CompletableFuture<Void> runAsync(Runnable runnable, Executor executor) {
		if (executor == null) {
			throw new IllegalArgumentException("executor required (never commonPool default)");
		}
		return CompletableFuture.runAsync(runnable, executor);
	}
}