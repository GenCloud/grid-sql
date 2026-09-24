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
package org.genfork.grid.sql.jepsen;

import io.netty.util.ResourceLeakDetector;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;

/**
 * Jepsen Docker node Boot entry (DDL bootstrap + optional HTTP liveness).
 * <p>
 * Product servers use {@code grid-sql-server-starter}. Sticky/promote discovery is wire
 * ServerMeta (not REST) — see {@code JepsenSqlClient} / RemoteConnectionFactory.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.0
 */
@SpringBootApplication(exclude = {
		TransactionAutoConfiguration.class
})
public class GridSqlJepsenStarter {
	public static void main(String[] args) {
		ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED);
		SpringApplication.run(GridSqlJepsenStarter.class, args);
	}
}
