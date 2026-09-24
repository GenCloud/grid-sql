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
package org.genfork.grid.sql.udf;

import java.util.List;

/**
 * Table-valued UDF SPI: returns row blobs (never string eval / scripting).
 * <p>
 * Column schema comes from {@code CREATE FUNCTION … RETURNS TABLE (…)}.
 * Implementations may scan a catalog table/view inside {@link #apply}.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
@FunctionalInterface
public interface SqlTableUdf {
	/**
	 * Produce row blobs matching the RETURNS TABLE column list.
	 *
	 * @param args positional args from {@code FROM fn(...)}
	 * @return row value blobs (may be empty, never null)
	 */
	List<byte[]> apply(Object[] args);
}