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
package org.genfork.grid.sql.client;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Optional client/configuration value (explicit present vs absent).
 *
 * @param <T> value type
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public final class Option<T> {
	private static final Option<?> NONE = new Option<>(false, null);

	private final boolean present;
	private final T value;

	private Option(boolean present, T value) {
		this.present = present;
		this.value = value;
	}

	@SuppressWarnings("unchecked")
	public static <T> Option<T> none() {
		return (Option<T>) NONE;
	}

	public static <T> Option<T> some(T value) {
		Objects.requireNonNull(value, "value");
		return new Option<>(true, value);
	}

	public static <T> Option<T> ofNullable(T value) {
		return value == null ? none() : some(value);
	}

	public boolean isPresent() {
		return present;
	}

	public boolean isEmpty() {
		return !present;
	}

	public T get() {
		if (!present) {
			throw new NoSuchElementException("Option.empty");
		}
		return value;
	}

	public T orElse(T other) {
		return present ? value : other;
	}

	public T orElseGet(Supplier<? extends T> supplier) {
		return present ? value : supplier.get();
	}

	public <U> Option<U> map(Function<? super T, ? extends U> mapper) {
		Objects.requireNonNull(mapper, "mapper");
		if (!present) {
			return none();
		}
		return ofNullable(mapper.apply(value));
	}

	@Override
	public String toString() {
		return present ? "Option[" + value + "]" : "Option.empty";
	}
}