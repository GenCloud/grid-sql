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
package index.sql.udf;

import org.genfork.grid.sql.udf.SqlMutatingUdf;
import org.genfork.grid.sql.udf.SqlUdfCallContext;

/**
 * Test mutating UDF: UPSERT into {@code items} via {@link SqlUdfCallContext#execute}.
 * <p>
 * Args: {@code (id INT, payload VARCHAR)}; returns the key id.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
public final class UpsertItemUdf implements SqlMutatingUdf {
	private static final String UPSERT_PREFIX = "UPSERT INTO items (id, payload) VALUES (";
	private static final String UPSERT_MID = ", '";
	private static final String UPSERT_SUFFIX = "')";
	private static final char SQL_QUOTE = '\'';
	private static final char SQL_QUOTE_ESCAPE = '\'';

	@Override
	public Object apply(Object[] args) {
		if (args == null || args.length != 2 || args[0] == null || args[1] == null) {
			throw new IllegalArgumentException("UpsertItemUdf requires (id, payload)");
		}
		final int id = ((Number) args[0]).intValue();
		final String payload = String.valueOf(args[1]);
		final String sql = UPSERT_PREFIX + id + UPSERT_MID + escapeLiteral(payload) + UPSERT_SUFFIX;
		SqlUdfCallContext.require().execute(sql);
		return id;
	}

	private static String escapeLiteral(String raw) {
		final StringBuilder sb = new StringBuilder(raw.length() + 8);
		for (int i = 0; i < raw.length(); i++) {
			final char c = raw.charAt(i);
			if (c == SQL_QUOTE) {
				sb.append(SQL_QUOTE_ESCAPE).append(SQL_QUOTE_ESCAPE);
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}
}