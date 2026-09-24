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
package org.genfork.grid.catalog;

import org.genfork.grid.fs.GridFs;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Minimal in-memory users, roles, and schema/table grants. Empty catalog keeps open-auth behavior.
 * <p>
 * Snapshot bytes ({@link #toSnapshotBytes()} / {@link #loadSnapshotBytes(byte[])}) are a compact
 * binary form for tests and optional {@code privileges.meta} persistence. Version 3 stores roles
 * and memberships; version 2 (PBKDF2 users/grants) and version 1 (legacy salted SHA-256) still load.
 *
 * @author: GenCloud
 * @date: 2025/07
 * @since: 1.0
 */
public final class PrivilegeCatalog {
	public static final String META_FILE_NAME = "privileges.meta";
	private static final int SALT_BYTES = 16;
	private static final int PBKDF2_ITERATIONS = 120_000;
	private static final int PBKDF2_KEY_BITS = 256;
	private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";
	private static final String LEGACY_HASH_ALGORITHM = "SHA-256";
	private static final int KDF_LEGACY_SHA256 = 1;
	private static final int KDF_PBKDF2 = 2;
	private static final String WILDCARD = "*";
	private static final String KEY_SEPARATOR = "\0";
	private static final int SNAPSHOT_MAGIC = 0x50524956;
	private static final int SNAPSHOT_VERSION_LEGACY = 1;
	private static final int SNAPSHOT_VERSION_V2 = 2;
	private static final int SNAPSHOT_VERSION = 3;

	private final Map<String, UserRecord> users = new ConcurrentHashMap<>();
	private final Map<String, Set<SqlPrivilege>> grants = new ConcurrentHashMap<>();
	private final Set<String> roles = ConcurrentHashMap.newKeySet();
	private final Map<String, Set<String>> memberships = new ConcurrentHashMap<>();
	private final Map<String, Set<SqlPrivilege>> roleGrants = new ConcurrentHashMap<>();
	private final SecureRandom random = new SecureRandom();
	private final ReentrantLock mutationLock = new ReentrantLock();
	private final Path metaFile;

	public PrivilegeCatalog() {
		this(null);
	}

	/**
	 * Create a privilege catalog optionally backed by {@code catalogDir/privileges.meta}.
	 *
	 * @param catalogDir table catalog directory, or {@code null} for memory-only operation
	 */
	public PrivilegeCatalog(Path catalogDir) {
		this.metaFile = catalogDir == null ? null : catalogDir.resolve(META_FILE_NAME);
		loadPersisted();
	}

	public boolean isOpen() {
		return users.isEmpty();
	}

	public void createUser(String user, String password) {
		final String normalized = normalize(user);
		if (normalized.isEmpty() || password == null) {
			throw new IllegalArgumentException("user and password required");
		}
		mutateAndPersist(() -> {
			final byte[] salt = new byte[SALT_BYTES];
			random.nextBytes(salt);
			final boolean administrator = users.isEmpty();
			final UserRecord record = new UserRecord(salt, hashPbkdf2(password, salt), administrator, KDF_PBKDF2);
			if (users.putIfAbsent(normalized, record) != null) {
				throw new IllegalStateException("User already exists: " + normalized);
			}
			if (administrator) {
				grants.computeIfAbsent(
								key(normalized, WILDCARD, WILDCARD),
								ignored -> ConcurrentHashMap.newKeySet())
						.addAll(EnumSet.of(SqlPrivilege.DDL));
			}
		});
	}

	/**
	 * Replace password for an existing user (PBKDF2). Requires the user to exist.
	 */
	public void changePassword(String user, String newPassword) {
		final String normalized = normalize(user);
		if (normalized.isEmpty() || newPassword == null) {
			throw new IllegalArgumentException("user and password required");
		}
		mutateAndPersist(() -> {
			final UserRecord existing = users.get(normalized);
			if (existing == null) {
				throw new IllegalStateException("Unknown user: " + normalized);
			}
			final byte[] salt = new byte[SALT_BYTES];
			random.nextBytes(salt);
			users.put(normalized, new UserRecord(salt, hashPbkdf2(newPassword, salt), existing.administrator(), KDF_PBKDF2));
		});
	}

	public void dropUser(String user) {
		final String normalized = normalize(user);
		mutateAndPersist(() -> {
			users.remove(normalized);
			final String prefix = normalized + KEY_SEPARATOR;
			grants.keySet().removeIf(key -> key.startsWith(prefix));
			memberships.remove(normalized);
		});
	}

	public void createRole(String role) {
		final String normalized = normalize(role);
		if (normalized.isEmpty()) {
			throw new IllegalArgumentException("role required");
		}
		mutateAndPersist(() -> {
			if (!roles.add(normalized)) {
				throw new IllegalStateException("Role already exists: " + normalized);
			}
		});
	}

	public void dropRole(String role) {
		final String normalized = normalize(role);
		mutateAndPersist(() -> {
			if (!roles.remove(normalized)) {
				throw new IllegalStateException("Unknown role: " + normalized);
			}
			final String prefix = normalized + KEY_SEPARATOR;
			roleGrants.keySet().removeIf(key -> key.startsWith(prefix));
			for (Set<String> assigned : memberships.values()) {
				assigned.remove(normalized);
			}
		});
	}

	public boolean roleExists(String role) {
		return roles.contains(normalize(role));
	}

	/**
	 * Grant role membership to a user ({@code GRANT ROLE r TO u}).
	 */
	public void grantRoleMembership(String user, String role) {
		final String normalizedUser = normalize(user);
		final String normalizedRole = normalize(role);
		mutateAndPersist(() -> {
			if (!users.containsKey(normalizedUser)) {
				throw new IllegalStateException("Unknown user: " + normalizedUser);
			}
			if (!roles.contains(normalizedRole)) {
				throw new IllegalStateException("Unknown role: " + normalizedRole);
			}
			memberships.computeIfAbsent(normalizedUser, ignored -> ConcurrentHashMap.newKeySet())
					.add(normalizedRole);
		});
	}

	/**
	 * Grant table/schema privileges to a role ({@code GRANT … TO ROLE r}).
	 */
	public void grantToRole(String role, String schema, String table, Set<SqlPrivilege> privileges) {
		final String normalized = normalize(role);
		mutateAndPersist(() -> {
			if (!roles.contains(normalized)) {
				throw new IllegalStateException("Unknown role: " + normalized);
			}
			roleGrants.computeIfAbsent(key(normalized, schema, table), ignored -> ConcurrentHashMap.newKeySet())
					.addAll(privileges);
		});
	}

	public boolean authenticate(String user, String password) {
		if (isOpen()) {
			return true;
		}
		final UserRecord record = users.get(normalize(user));
		return record != null && password != null
				&& MessageDigest.isEqual(record.passwordHash(), hashFor(record, password));
	}

	public boolean userExists(String user) {
		return users.containsKey(normalize(user));
	}

	public boolean isAdministrator(String user) {
		final UserRecord record = users.get(normalize(user));
		return record != null && record.administrator();
	}

	public void grant(String user, String schema, String table, Set<SqlPrivilege> privileges) {
		final String normalized = normalize(user);
		mutateAndPersist(() -> {
			if (!users.containsKey(normalized)) {
				throw new IllegalStateException("Unknown user: " + normalized);
			}
			grants.computeIfAbsent(key(normalized, schema, table), ignored -> ConcurrentHashMap.newKeySet())
					.addAll(privileges);
		});
	}

	public void revoke(String user, String schema, String table, Set<SqlPrivilege> privileges) {
		mutateAndPersist(() -> {
			final Set<SqlPrivilege> assigned = grants.get(key(normalize(user), schema, table));
			if (assigned != null) {
				assigned.removeAll(privileges);
			}
		});
	}

	public void ensure(String user, String schema, String table, SqlPrivilege privilege) {
		if (isOpen() || has(user, schema, table, privilege) || has(user, schema, WILDCARD, privilege)
				|| has(user, WILDCARD, WILDCARD, privilege)) {
			return;
		}
		throw new SecurityException("permission denied: " + privilege + " on " + schema + "." + table);
	}

	/**
	 * Serialize users, grants, roles, and memberships for persistence / test round-trip.
	 */
	public byte[] toSnapshotBytes() {
		mutationLock.lock();
		try {
			final ByteArrayOutputStream raw = new ByteArrayOutputStream();
			final DataOutputStream out = new DataOutputStream(raw);
			out.writeInt(SNAPSHOT_MAGIC);
			out.writeInt(SNAPSHOT_VERSION);
			out.writeInt(users.size());
			for (Map.Entry<String, UserRecord> entry : users.entrySet()) {
				writeUtf(out, entry.getKey());
				final UserRecord record = entry.getValue();
				out.writeInt(record.salt().length);
				out.write(record.salt());
				out.writeInt(record.passwordHash().length);
				out.write(record.passwordHash());
				out.writeBoolean(record.administrator());
				out.writeInt(record.kdfKind());
			}
			out.writeInt(grants.size());
			for (Map.Entry<String, Set<SqlPrivilege>> entry : grants.entrySet()) {
				writeUtf(out, entry.getKey());
				out.writeInt(entry.getValue().size());
				for (SqlPrivilege privilege : entry.getValue()) {
					writeUtf(out, privilege.name());
				}
			}
			out.writeInt(roles.size());
			for (String role : roles) {
				writeUtf(out, role);
			}
			out.writeInt(memberships.size());
			for (Map.Entry<String, Set<String>> entry : memberships.entrySet()) {
				writeUtf(out, entry.getKey());
				out.writeInt(entry.getValue().size());
				for (String role : entry.getValue()) {
					writeUtf(out, role);
				}
			}
			out.writeInt(roleGrants.size());
			for (Map.Entry<String, Set<SqlPrivilege>> entry : roleGrants.entrySet()) {
				writeUtf(out, entry.getKey());
				out.writeInt(entry.getValue().size());
				for (SqlPrivilege privilege : entry.getValue()) {
					writeUtf(out, privilege.name());
				}
			}
			out.flush();
			return raw.toByteArray();
		} catch (IOException ex) {
			throw new IllegalStateException("privilege snapshot encode failed", ex);
		} finally {
			mutationLock.unlock();
		}
	}

	/**
	 * Replace catalog contents from {@link #toSnapshotBytes()} payload.
	 */
	public void loadSnapshotBytes(byte[] bytes) {
		mutationLock.lock();
		try {
			final byte[] previous = toSnapshotBytes();
			try {
				replaceSnapshotBytes(bytes);
				persist();
			} catch (RuntimeException ex) {
				replaceSnapshotBytes(previous);
				throw ex;
			}
		} finally {
			mutationLock.unlock();
		}
	}

	private void replaceSnapshotBytes(byte[] bytes) {
		if (bytes == null || bytes.length == 0) {
			users.clear();
			grants.clear();
			roles.clear();
			memberships.clear();
			roleGrants.clear();
			return;
		}
		try {
			final DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
			final int magic = in.readInt();
			if (magic != SNAPSHOT_MAGIC) {
				throw new IllegalArgumentException("bad privilege snapshot magic");
			}
			final int version = in.readInt();
			if (version != SNAPSHOT_VERSION
					&& version != SNAPSHOT_VERSION_V2
					&& version != SNAPSHOT_VERSION_LEGACY) {
				throw new IllegalArgumentException("unsupported privilege snapshot version: " + version);
			}
			final Map<String, UserRecord> loadedUsers = new ConcurrentHashMap<>();
			final int userCount = in.readInt();
			for (int i = 0; i < userCount; i++) {
				final String name = readUtf(in);
				final int saltLen = in.readInt();
				final byte[] salt = in.readNBytes(saltLen);
				final int hashLen = in.readInt();
				final byte[] passwordHash = in.readNBytes(hashLen);
				final boolean administrator = in.readBoolean();
				final int kdfKind = version == SNAPSHOT_VERSION_LEGACY ? KDF_LEGACY_SHA256 : in.readInt();
				loadedUsers.put(name, new UserRecord(salt, passwordHash, administrator, kdfKind));
			}
			final Map<String, Set<SqlPrivilege>> loadedGrants = new ConcurrentHashMap<>();
			final int grantCount = in.readInt();
			for (int i = 0; i < grantCount; i++) {
				final String grantKey = readUtf(in);
				final int privilegeCount = in.readInt();
				final Set<SqlPrivilege> assigned = ConcurrentHashMap.newKeySet();
				for (int p = 0; p < privilegeCount; p++) {
					assigned.add(SqlPrivilege.valueOf(readUtf(in)));
				}
				loadedGrants.put(grantKey, assigned);
			}
			final Set<String> loadedRoles = ConcurrentHashMap.newKeySet();
			final Map<String, Set<String>> loadedMemberships = new ConcurrentHashMap<>();
			final Map<String, Set<SqlPrivilege>> loadedRoleGrants = new ConcurrentHashMap<>();
			if (version >= SNAPSHOT_VERSION) {
				final int roleCount = in.readInt();
				for (int i = 0; i < roleCount; i++) {
					loadedRoles.add(readUtf(in));
				}
				final int membershipCount = in.readInt();
				for (int i = 0; i < membershipCount; i++) {
					final String user = readUtf(in);
					final int roleCountForUser = in.readInt();
					final Set<String> assigned = ConcurrentHashMap.newKeySet();
					for (int r = 0; r < roleCountForUser; r++) {
						assigned.add(readUtf(in));
					}
					loadedMemberships.put(user, assigned);
				}
				final int roleGrantCount = in.readInt();
				for (int i = 0; i < roleGrantCount; i++) {
					final String grantKey = readUtf(in);
					final int privilegeCount = in.readInt();
					final Set<SqlPrivilege> assigned = ConcurrentHashMap.newKeySet();
					for (int p = 0; p < privilegeCount; p++) {
						assigned.add(SqlPrivilege.valueOf(readUtf(in)));
					}
					loadedRoleGrants.put(grantKey, assigned);
				}
			}
			users.clear();
			users.putAll(loadedUsers);
			grants.clear();
			grants.putAll(loadedGrants);
			roles.clear();
			roles.addAll(loadedRoles);
			memberships.clear();
			memberships.putAll(loadedMemberships);
			roleGrants.clear();
			roleGrants.putAll(loadedRoleGrants);
		} catch (IOException ex) {
			throw new IllegalStateException("privilege snapshot decode failed", ex);
		}
	}

	private void mutateAndPersist(Runnable mutation) {
		mutationLock.lock();
		try {
			final byte[] previous = toSnapshotBytes();
			try {
				mutation.run();
				persist();
			} catch (RuntimeException ex) {
				replaceSnapshotBytes(previous);
				throw ex;
			}
		} finally {
			mutationLock.unlock();
		}
	}

	private void persist() {
		if (metaFile == null) {
			return;
		}
		try {
			GridFs.writeAtomic(metaFile, toSnapshotBytes());
		} catch (IOException ex) {
			throw new IllegalStateException("Failed to persist privilege catalog " + metaFile, ex);
		}
	}

	private void loadPersisted() {
		if (!GridFs.isRegularFile(metaFile)) {
			return;
		}
		try {
			replaceSnapshotBytes(GridFs.readAll(metaFile));
		} catch (IOException ex) {
			throw new IllegalStateException("Failed to read privilege catalog " + metaFile, ex);
		}
	}

	private boolean has(String user, String schema, String table, SqlPrivilege privilege) {
		final String normalizedUser = normalize(user);
		final Set<SqlPrivilege> assigned = grants.get(key(normalizedUser, schema, table));
		if (assigned != null && assigned.contains(privilege)) {
			return true;
		}
		final Set<String> userRoles = memberships.get(normalizedUser);
		if (userRoles == null || userRoles.isEmpty()) {
			return false;
		}
		for (String role : userRoles) {
			final Set<SqlPrivilege> roleAssigned = roleGrants.get(key(role, schema, table));
			if (roleAssigned != null && roleAssigned.contains(privilege)) {
				return true;
			}
		}
		return false;
	}

	private static String key(String principal, String schema, String table) {
		return principal + KEY_SEPARATOR + normalizeTarget(schema) + KEY_SEPARATOR + normalizeTarget(table);
	}

	private static String normalizeTarget(String target) {
		return target == null || target.isBlank() ? WILDCARD : target.trim().toLowerCase(Locale.ROOT);
	}

	private static String normalize(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}

	private static byte[] hashFor(UserRecord record, String password) {
		if (record.kdfKind() == KDF_PBKDF2) {
			return hashPbkdf2(password, record.salt());
		}
		return hashLegacySha256(password, record.salt());
	}

	private static byte[] hashPbkdf2(String password, byte[] salt) {
		try {
			final PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS);
			final SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);
			return factory.generateSecret(spec).getEncoded();
		} catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
			throw new IllegalStateException(PBKDF2_ALGORITHM + " unavailable", ex);
		}
	}

	private static byte[] hashLegacySha256(String password, byte[] salt) {
		try {
			final MessageDigest digest = MessageDigest.getInstance(LEGACY_HASH_ALGORITHM);
			digest.update(salt);
			return digest.digest(password.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(LEGACY_HASH_ALGORITHM + " unavailable", ex);
		}
	}

	private static void writeUtf(DataOutputStream out, String value) throws IOException {
		final byte[] raw = value.getBytes(StandardCharsets.UTF_8);
		out.writeInt(raw.length);
		out.write(raw);
	}

	private static String readUtf(DataInputStream in) throws IOException {
		final int length = in.readInt();
		final byte[] raw = in.readNBytes(length);
		return new String(raw, StandardCharsets.UTF_8);
	}

	/**
	 * Salted credential, bootstrap administrator marker, and KDF kind.
	 *
	 * @author: GenCloud
	 * @date: 2025/07
	 * @since: 1.0
	 */
	private record UserRecord(byte[] salt, byte[] passwordHash, boolean administrator, int kdfKind) {
	}
}
