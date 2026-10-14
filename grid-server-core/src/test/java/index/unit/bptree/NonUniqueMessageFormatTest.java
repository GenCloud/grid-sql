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
package index.unit.bptree;

import org.genfork.grid.exceptions.NonUniqueValueException;
import org.genfork.grid.mem.index.IndexPointerRef;
import org.genfork.grid.mem.index.btree.BPValueHelper;
import org.genfork.grid.mem.index.btree.GridPointerBPTree;
import org.genfork.grid.mem.index.btree.SingleTreeKey;
import org.genfork.grid.serial.SqlWireUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STRICT NonUnique messages must carry domain + index name labels.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.2
 */
class NonUniqueMessageFormatTest {
	private static final String INDEX_NAME = "demo_strict_idx";

	@Test
	void bptreeStrictMessageIncludesIndexName() {
		final GridPointerBPTree tree = new GridPointerBPTree(INDEX_NAME, true);
		final byte[] keyBytes = SqlWireUtil.toGenericArray(1);
		final byte[] valueBytes = SqlWireUtil.toGenericArray("v");
		final long nativeRef = BPValueHelper.toNativeValueRef(keyBytes, null);
		final IndexPointerRef pointer = new IndexPointerRef(nativeRef);
		final SingleTreeKey treeKey = new SingleTreeKey(valueBytes);
		tree.insert(treeKey, pointer);

		final NonUniqueValueException ex = assertThrows(
				NonUniqueValueException.class,
				() -> tree.insert(treeKey, pointer));
		final String msg = ex.getMessage();
		assertTrue(msg.contains("domain="), msg);
		assertTrue(msg.contains("index=" + INDEX_NAME), msg);
		assertTrue(msg.contains("not unique for strict index"), msg);
	}

	@Test
	void formatMessageCanonicalShape() {
		final String msg = NonUniqueValueException.formatMessage(
				"shared_game_server.player_hwnd",
				"player_hwnd_pk",
				"k");
		assertTrue(msg.startsWith("domain=shared_game_server.player_hwnd index=player_hwnd_pk value=["));
		assertTrue(msg.endsWith("] not unique for strict index."));
	}
}
