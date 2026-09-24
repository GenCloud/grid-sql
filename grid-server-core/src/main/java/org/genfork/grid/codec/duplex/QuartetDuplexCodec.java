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
package org.genfork.grid.codec.duplex;

/**
 * Logical bytes ↔ duplex codec. Prefer {@link #encodeLogical(byte[])} for SQL-first blobs.
 *
 * @author: GenCloud
 * @date: 2025/06
 * @since: 1.0
 */
public class QuartetDuplexCodec {
	private final DuplexVerifier verifier;
	private long schemaEpoch = 1L;
	private boolean verifyOnWrite = true;
	private boolean verifyOnRead = true;

	public QuartetDuplexCodec(DuplexRepairMode repairMode) {
		this.verifier = new DuplexVerifier(repairMode);
	}

	public DuplexVerifier getVerifier() {
		return verifier;
	}

	public long getSchemaEpoch() {
		return schemaEpoch;
	}

	public void setSchemaEpoch(long schemaEpoch) {
		this.schemaEpoch = schemaEpoch;
	}

	public boolean isVerifyOnWrite() {
		return verifyOnWrite;
	}

	public void setVerifyOnWrite(boolean verifyOnWrite) {
		this.verifyOnWrite = verifyOnWrite;
	}

	public boolean isVerifyOnRead() {
		return verifyOnRead;
	}

	public void setVerifyOnRead(boolean verifyOnRead) {
		this.verifyOnRead = verifyOnRead;
	}

	/** Pack already-built logical bytes into duplex lanes. */
	public DuplexBlob encodeLogical(byte[] logical) {
		final byte[] data = QuartetPacker.pack(logical);
		final byte[] parity = ParityLane.fromDataLane(data);
		DuplexBlob blob = new DuplexBlob(data, parity, logical.length, schemaEpoch,
				DuplexBlob.checksum(data, parity, logical.length, schemaEpoch));
		if (verifyOnWrite) {
			blob = verifier.verifyOrRepair(blob);
		}
		return blob;
	}

	public byte[] decodeToLogical(DuplexBlob blob) {
		DuplexBlob verified = blob;
		if (verifyOnRead) {
			verified = verifier.verifyOrRepair(blob);
		}
		return QuartetPacker.unpack(verified.dataLane(), verified.logicalLen());
	}
}
