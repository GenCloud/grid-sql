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
package org.genfork.grid.sql.server;

import io.netty.util.ResourceLeakDetector;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;

/**
 * Product Boot entry for SQL TCP fat-jar server (DBeaver / IDE / production).
 * <p>
 * Jepsen nodes use {@code grid-sql-jepsen-starter}. Zero-Spring: {@code SqlServerMain}.
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
@SpringBootApplication(exclude = {
		TransactionAutoConfiguration.class
})
public class GridSqlServerStarter {
	public static void main(String[] args) {
		ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED);
		SpringApplication.run(GridSqlServerStarter.class, args);
	}
}
