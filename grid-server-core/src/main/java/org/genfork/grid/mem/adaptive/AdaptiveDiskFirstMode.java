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
package org.genfork.grid.mem.adaptive;

/**
 * Hysteresis bands for adaptive disk-first working-set policy.
 * <p>
 * Sealed GMAP + OpLog remain source of truth; mode only controls RAM accelerator pressure.
 *
 * @author: GenCloud
 * @date: 2025/02
 * @since: 1.0
 */
public enum AdaptiveDiskFirstMode {
	/** Relax toward configured WS ceiling; allow warm residency for hot keys. */
	LOW,
	/** Balanced WS cap; hydrate follows configured {@code hydrateMode}. */
	NORMAL,
	/** Force LAZY semantics, tighten WS cap, prefer sealed miss reads. */
	HIGH
}
