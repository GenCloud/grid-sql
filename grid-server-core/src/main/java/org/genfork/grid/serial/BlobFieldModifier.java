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
package org.genfork.grid.serial;

import org.genfork.grid.catalog.TableSchema;
import org.genfork.grid.replication.codec.ModifyPayload.Decoded;

import java.util.List;

/**
 * Applies structural field modifies on storage {@code byte[]} without a client-facing entity.
 * <p>
 * Hot path: {@link LogicalFieldCursor} multi-assign rewrite + {@code encodeLogical} when duplex.
 *
 * @author: GenCloud
 * @date: 2025/06
 * @since: 1.0
 */
public final class BlobFieldModifier {
    private BlobFieldModifier() {
    }

    /**
     * Catalog / SQL-first path: cursor rewrite only (no POJO fallback).
     */
    public static byte[] apply(TableSchema schema, byte[] existing, List<Decoded> assigns) {
        if (assigns == null || assigns.isEmpty()) {
            throw new IllegalArgumentException("assigns empty");
        }
        if (existing == null) {
            throw new IllegalStateException("UPDATE target row missing for key");
        }
        if (!LogicalFieldCursor.canOpen(schema, existing)) {
            throw new IllegalStateException("Cannot open row cursor for catalog UPDATE");
        }
        return LogicalFieldCursor.open(schema, existing).rewrite(assigns);
    }
}