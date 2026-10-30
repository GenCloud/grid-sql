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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Password hashing and length-prefixed UTF-8 I/O for {@link PrivilegeCatalog} snapshots.
 *
 * @author: GenCloud
 * @date: 2026/10
 * @since: 1.3
 */
public final class PrivilegeCryptoIoUtil {
	public static final int KDF_LEGACY_SHA256 = 1;
	public static final int KDF_PBKDF2 = 2;

	private static final int PBKDF2_ITERATIONS = 120_000;
	private static final int PBKDF2_KEY_BITS = 256;
	private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";
	private static final String LEGACY_HASH_ALGORITHM = "SHA-256";

	private PrivilegeCryptoIoUtil() {
	}

	public static byte[] hashFor(int kdfKind, byte[] salt, String password) {
		if (kdfKind == KDF_PBKDF2) {
			return hashPbkdf2(password, salt);
		}
		return hashLegacySha256(password, salt);
	}

	public static byte[] hashPbkdf2(String password, byte[] salt) {
		try {
			final PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS);
			final SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);
			return factory.generateSecret(spec).getEncoded();
		} catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
			throw new IllegalStateException(PBKDF2_ALGORITHM + " unavailable", ex);
		}
	}

	public static byte[] hashLegacySha256(String password, byte[] salt) {
		try {
			final MessageDigest digest = MessageDigest.getInstance(LEGACY_HASH_ALGORITHM);
			digest.update(salt);
			return digest.digest(password.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(LEGACY_HASH_ALGORITHM + " unavailable", ex);
		}
	}

	public static void writeUtf(DataOutputStream out, String value) throws IOException {
		final byte[] raw = value.getBytes(StandardCharsets.UTF_8);
		out.writeInt(raw.length);
		out.write(raw);
	}

	public static String readUtf(DataInputStream in) throws IOException {
		final int length = in.readInt();
		final byte[] raw = in.readNBytes(length);
		return new String(raw, StandardCharsets.UTF_8);
	}
}
