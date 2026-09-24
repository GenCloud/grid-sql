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

/**
 * Marker for scalar UDFs that may mutate via {@link SqlUdfCallContext} in the same session/TX.
 * <p>
 * Implementations still use {@link SqlUdf#apply(Object[])}; read {@link SqlUdfCallContext#require()}
 * inside {@code apply} to run nested SQL. Bound at {@code CREATE FUNCTION} when the class
 * implements this interface ({@code FunctionDef.mutating=true}).
 *
 * @author: GenCloud
 * @date: 2025/11
 * @since: 1.0
 */
@FunctionalInterface
public interface SqlMutatingUdf extends SqlUdf {
}