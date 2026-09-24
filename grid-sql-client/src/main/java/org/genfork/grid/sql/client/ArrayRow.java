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

/**
 * Row backed by Object[] cells.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public final class ArrayRow implements Row {
	private final Object[] values;
	private final DefaultRowMetadata metadata;

	public ArrayRow(Object[] values, DefaultRowMetadata metadata) {
		this.values = values;
		this.metadata = metadata;
	}

	@Override
	public Object get(int index) {
		return values[index];
	}

	@Override
	public Object get(String name) {
		return values[metadata.indexOf(name)];
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> T get(int index, Class<T> type) {
		return (T) values[index];
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> T get(String name, Class<T> type) {
		return (T) get(name);
	}
}