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
package index.unit.catalog;

import org.genfork.grid.catalog.PrivilegeCatalog;
import org.genfork.grid.catalog.SqlPrivilege;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author: GenCloud
 * @date: 2025/07
 * @since: 1.0
 */
class PrivilegeCatalogTest {
	private static final int SNAPSHOT_VERSION_V2 = 2;

	@Test
	void openCatalogAllowsAll() {
		final PrivilegeCatalog cat = new PrivilegeCatalog();
		assertTrue(cat.isOpen());
		assertDoesNotThrow(() -> cat.ensure("u", "public", "t", SqlPrivilege.SELECT));
	}

	@Test
	void denySelectWithoutGrant() {
		final PrivilegeCatalog cat = new PrivilegeCatalog();
		cat.createUser("alice", "secret");
		assertThrows(SecurityException.class,
				() -> cat.ensure("alice", "public", "t", SqlPrivilege.SELECT));
		cat.grant("alice", "public", "t", EnumSet.of(SqlPrivilege.SELECT));
		assertDoesNotThrow(() -> cat.ensure("alice", "public", "t", SqlPrivilege.SELECT));
	}

	@Test
	void snapshotRoundTripPreservesAuthAndGrants() {
		final PrivilegeCatalog cat = new PrivilegeCatalog();
		cat.createUser("admin", "secret");
		cat.createUser("alice", "pw");
		cat.grant("alice", "public", "t", EnumSet.of(SqlPrivilege.SELECT));
		final byte[] bytes = cat.toSnapshotBytes();

		final PrivilegeCatalog loaded = new PrivilegeCatalog();
		loaded.loadSnapshotBytes(bytes);
		assertFalse(loaded.isOpen());
		assertTrue(loaded.authenticate("alice", "pw"));
		assertFalse(loaded.authenticate("alice", "wrong"));
		assertDoesNotThrow(() -> loaded.ensure("alice", "public", "t", SqlPrivilege.SELECT));
		assertThrows(SecurityException.class,
				() -> loaded.ensure("alice", "public", "t", SqlPrivilege.INSERT));
	}

	@Test
	void changePasswordUsesNewCredential() {
		final PrivilegeCatalog cat = new PrivilegeCatalog();
		cat.createUser("alice", "old");
		assertTrue(cat.authenticate("alice", "old"));
		cat.changePassword("alice", "new");
		assertFalse(cat.authenticate("alice", "old"));
		assertTrue(cat.authenticate("alice", "new"));
	}

	@Test
	void roleGrantMembershipAndSnapshotRoundTrip() {
		final PrivilegeCatalog cat = new PrivilegeCatalog();
		cat.createUser("admin", "secret");
		cat.createUser("alice", "pw");
		cat.createRole("readers");
		cat.grantToRole("readers", "public", "t", EnumSet.of(SqlPrivilege.SELECT));
		cat.grantRoleMembership("alice", "readers");
		assertDoesNotThrow(() -> cat.ensure("alice", "public", "t", SqlPrivilege.SELECT));
		assertThrows(SecurityException.class,
				() -> cat.ensure("alice", "public", "t", SqlPrivilege.INSERT));

		final byte[] bytes = cat.toSnapshotBytes();
		final PrivilegeCatalog loaded = new PrivilegeCatalog();
		loaded.loadSnapshotBytes(bytes);
		assertTrue(loaded.roleExists("readers"));
		assertDoesNotThrow(() -> loaded.ensure("alice", "public", "t", SqlPrivilege.SELECT));
		assertThrows(SecurityException.class,
				() -> loaded.ensure("alice", "public", "t", SqlPrivilege.INSERT));
	}

	@Test
	void v2SnapshotMigratesWithoutRoles() throws IOException {
		final PrivilegeCatalog source = new PrivilegeCatalog();
		source.createUser("admin", "secret");
		source.createUser("bob", "pw");
		source.grant("bob", "public", "t", EnumSet.of(SqlPrivilege.SELECT));
		final byte[] legacy = stripToV2(source.toSnapshotBytes());

		final PrivilegeCatalog loaded = new PrivilegeCatalog();
		loaded.loadSnapshotBytes(legacy);
		assertTrue(loaded.authenticate("bob", "pw"));
		assertFalse(loaded.roleExists("any"));
		assertDoesNotThrow(() -> loaded.ensure("bob", "public", "t", SqlPrivilege.SELECT));
	}

	/** Truncate a v3 snapshot to the v2 users+grants prefix for migration coverage. */
	private static byte[] stripToV2(byte[] v3) throws IOException {
		final DataInputStream in = new DataInputStream(new ByteArrayInputStream(v3));
		final ByteArrayOutputStream raw = new ByteArrayOutputStream();
		final DataOutputStream out = new DataOutputStream(raw);
		out.writeInt(in.readInt());
		in.readInt();
		out.writeInt(SNAPSHOT_VERSION_V2);
		final int userCount = in.readInt();
		out.writeInt(userCount);
		for (int i = 0; i < userCount; i++) {
			copyUtf(in, out);
			final int saltLen = in.readInt();
			out.writeInt(saltLen);
			out.write(in.readNBytes(saltLen));
			final int hashLen = in.readInt();
			out.writeInt(hashLen);
			out.write(in.readNBytes(hashLen));
			out.writeBoolean(in.readBoolean());
			out.writeInt(in.readInt());
		}
		final int grantCount = in.readInt();
		out.writeInt(grantCount);
		for (int i = 0; i < grantCount; i++) {
			copyUtf(in, out);
			final int privilegeCount = in.readInt();
			out.writeInt(privilegeCount);
			for (int p = 0; p < privilegeCount; p++) {
				copyUtf(in, out);
			}
		}
		out.flush();
		return raw.toByteArray();
	}

	private static void copyUtf(DataInputStream in, DataOutputStream out) throws IOException {
		final int length = in.readInt();
		final byte[] bytes = in.readNBytes(length);
		out.writeInt(length);
		out.write(bytes);
	}
}
