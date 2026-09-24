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
package org.genfork.grid.sql.client.transport;

import java.util.List;

import org.genfork.grid.sql.SqlResult;
import org.genfork.grid.sql.client.ServerMeta;

/**
 * Reactor-free EXEC / AUTH / SESSION outcome from Sync / reactive exec exchanges.
 *
 * @author: GenCloud
 * @date: 2026/08
 * @since: 1.0
 */
public sealed interface TransportOutcome
		permits TransportOutcome.Auth,
		TransportOutcome.SessionOpen,
		TransportOutcome.SessionClose,
		TransportOutcome.Dml,
		TransportOutcome.ResultSet {

	/**
	 * AUTH_OK completed.
	 *
	 * @author: GenCloud
	 * @date: 2026/08
	 * @since: 1.0
	 */
	record Auth(ServerMeta serverMeta) implements TransportOutcome {
		public Auth {
			serverMeta = serverMeta == null ? ServerMeta.EMPTY : serverMeta;
		}
	}

	/**
	 * SESSION_OPEN_OK completed ({@code sessionId} as rows-affected on the wire).
	 *
	 * @author: GenCloud
	 * @date: 2026/08
	 * @since: 1.0
	 */
	record SessionOpen(int sessionId) implements TransportOutcome {
	}

	/**
	 * SESSION_CLOSE_OK completed.
	 *
	 * @author: GenCloud
	 * @date: 2026/08
	 * @since: 1.0
	 */
	record SessionClose() implements TransportOutcome {
	}

	/**
	 * Non-streaming EXEC_DONE (DDL / DML / CANCEL).
	 *
	 * @author: GenCloud
	 * @date: 2026/08
	 * @since: 1.0
	 */
	record Dml(long affected, String tag) implements TransportOutcome {
		public Dml {
			tag = tag == null ? "" : tag;
		}
	}

	/**
	 * Streaming RESULT_SET: metadata available at ROW_DESC; rows via {@link RowPortal}.
	 *
	 * @author: GenCloud
	 * @date: 2026/08
	 * @since: 1.0
	 */
	record ResultSet(List<SqlResult.ColumnMeta> columns, RowPortal portal) implements TransportOutcome {
		public ResultSet {
			columns = columns == null ? List.of() : List.copyOf(columns);
			if (portal == null) {
				throw new IllegalArgumentException("portal required");
			}
		}
	}
}
