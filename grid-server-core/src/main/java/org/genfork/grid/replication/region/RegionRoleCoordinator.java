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
package org.genfork.grid.replication.region;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.google.common.annotations.VisibleForTesting;

/**
 * Multi-DC Active/Hold/Witness state machine with durable epoch fencing.
 * <p>
 * Active admits writes only while local epoch equals the observed cluster epoch view
 * <b>and</b> epoch is confirmed against a remote-DC peer (revive must not dual-write
 * before HELLO fence). Higher observed epoch fences Active to Hold (never auto-reclaim
 * on revive). Claim lease uses Netty peer-link reachability (see {@link #noteActivePeerLink()}):
 * no separate region heartbeat beyond channel lifecycle.
 *
 * @author: GenCloud
 * @date: 2026/06
 * @since: 1.0
 */
public final class RegionRoleCoordinator implements AutoCloseable {
	private static final String REGION_DIR = "region";
	private static final long DEFAULT_CLAIM_TIMEOUT_MS = 5_000L;

	private final AtomicReference<RegionLeaseState> lease;
	private final AtomicLong observedClusterEpoch;
	/**
	 * Active after cold start / revive stays false until a remote-DC peer HELLO confirms
	 * we are not behind a newer claim (or dual-Active at the same epoch).
	 */
	private final AtomicBoolean epochConfirmed;
	private final RegionEpochStore store;
	private final String localNodeId;
	private final String localDc;
	private final long claimTimeoutMs;
	private final int claimQuorumVoters;
	/** Last time an Active remote-DC Netty peer link was observed reachable. */
	private final AtomicLong lastActivePeerLinkNanos;
	private final boolean enabled;

	public RegionRoleCoordinator(
			Path dataDir,
			boolean fsync,
			boolean enabled,
			RegionRole bootstrapRole,
			long bootstrapEpoch,
			String localNodeId,
			String localDc,
			long claimTimeoutMs,
			int claimQuorumVoters
	) throws IOException {
		this.enabled = enabled;
		this.localNodeId = localNodeId == null ? "" : localNodeId;
		this.localDc = localDc == null ? "" : localDc;
		this.claimTimeoutMs = claimTimeoutMs > 0 ? claimTimeoutMs : DEFAULT_CLAIM_TIMEOUT_MS;
		this.claimQuorumVoters = Math.max(1, claimQuorumVoters);
		this.lastActivePeerLinkNanos = new AtomicLong(System.nanoTime());
		final RegionLeaseState boot = RegionLeaseState.bootstrap(
				bootstrapRole == null ? RegionRole.ACTIVE : bootstrapRole,
				bootstrapEpoch <= 0 ? RegionLeaseState.INITIAL_EPOCH : bootstrapEpoch,
				this.localNodeId,
				this.localDc);
		if (!enabled) {
			this.store = null;
			this.lease = new AtomicReference<>(boot);
			this.observedClusterEpoch = new AtomicLong(boot.epoch());
			this.epochConfirmed = new AtomicBoolean(true);
			return;
		}
		this.store = new RegionEpochStore(dataDir.resolve(REGION_DIR), fsync);
		final RegionLeaseState loaded = store.loadOrDefault(boot);
		this.lease = new AtomicReference<>(loaded);
		this.observedClusterEpoch = new AtomicLong(loaded.epoch());
		// Hold/Witness never admit writes; Active must confirm vs remote-DC before writes.
		this.epochConfirmed = new AtomicBoolean(loaded.role() != RegionRole.ACTIVE);
	}

	public boolean isEnabled() {
		return enabled;
	}

	public RegionLeaseState lease() {
		return lease.get();
	}

	public long regionEpoch() {
		return lease.get().epoch();
	}

	public RegionRole regionRole() {
		return lease.get().role();
	}

	public long observedClusterEpoch() {
		return observedClusterEpoch.get();
	}

	/**
	 * Region admits writes only when Active, epoch matches observed cluster epoch,
	 * and revive epoch is confirmed against a remote-DC peer.
	 */
	public boolean regionAllowsWrites() {
		if (!enabled) {
			return true;
		}
		if (!epochConfirmed.get()) {
			return false;
		}
		return RegionEpochOps.regionAllowsWrites(lease.get(), observedClusterEpoch.get());
	}

	/**
	 * True when Active has confirmed it is not behind a remote claim (or role is non-Active).
	 */
	public boolean isEpochConfirmed() {
		return !enabled || epochConfirmed.get();
	}

	/**
	 * Observe peer meta epoch; fence local Active if peer is ahead.
	 */
	public boolean observePeerEpoch(long peerEpoch, String peerNodeId) {
		if (!enabled || peerEpoch <= 0) {
			return false;
		}
		observedClusterEpoch.accumulateAndGet(peerEpoch, Math::max);
		final RegionLeaseState cur = lease.get();
		if (RegionEpochOps.mustFenceOnHigherEpoch(cur.epoch(), peerEpoch)
				&& cur.role() == RegionRole.ACTIVE) {
			fenceToHold();
			return true;
		}
		return false;
	}

	/**
	 * After remote-DC HELLO: fence dual-Active at same epoch, or confirm local Active
	 * when peer epoch is at or behind ours (revive catch-up fence complete).
	 *
	 * @return true when this call fenced Active → Hold
	 */
	public boolean noteRemotePeerRegion(long peerEpoch, byte peerRoleWire) {
		if (!enabled || peerEpoch <= 0) {
			return false;
		}
		observedClusterEpoch.accumulateAndGet(peerEpoch, Math::max);
		final RegionLeaseState cur = lease.get();
		if (RegionEpochOps.mustFenceOnHigherEpoch(cur.epoch(), peerEpoch)
				&& cur.role() == RegionRole.ACTIVE) {
			fenceToHold();
			return true;
		}
		if (RegionEpochOps.mustFenceDualActive(
				cur.role(), cur.epoch(), peerRoleWire, peerEpoch, true)) {
			fenceToHold();
			return true;
		}
		if (!epochConfirmed.get()
				&& cur.role() == RegionRole.ACTIVE
				&& RegionEpochOps.canConfirmEpochAfterRemotePeer(cur.epoch(), peerEpoch)) {
			epochConfirmed.set(true);
		}
		return false;
	}

	/**
	 * Active remote-DC Netty channel is up — resets claim silence clock.
	 */
	public void noteActivePeerLink() {
		lastActivePeerLinkNanos.set(System.nanoTime());
	}

	/**
	 * Configured Hold/Witness voter count used for claim majority math.
	 */
	public int claimQuorumVoters() {
		return claimQuorumVoters;
	}

	/**
	 * Hold may start a claim only when Active peers are unreachable <b>and</b>
	 * the peer-link has been down past {@code claimTimeoutMs} (Netty channel lifecycle).
	 */
	public boolean shouldAttemptClaim(boolean activePeersReachable) {
		if (!enabled) {
			return false;
		}
		final RegionLeaseState cur = lease.get();
		if (cur.role() != RegionRole.HOLD && cur.role() != RegionRole.WITNESS) {
			return false;
		}
		if (activePeersReachable) {
			noteActivePeerLink();
			return false;
		}
		final long silentMs = (System.nanoTime() - lastActivePeerLinkNanos.get()) / 1_000_000L;
		return silentMs >= claimTimeoutMs;
	}

	/**
	 * Apply successful claim after quorum ACKs (caller supplies ack count).
	 */
	public boolean tryCompleteClaim(int ackCount) {
		if (!enabled) {
			return false;
		}
		if (!RegionClaimQuorum.hasQuorum(ackCount, claimQuorumVoters)) {
			return false;
		}
		final RegionLeaseState cur = lease.get();
		final long next = RegionEpochOps.nextClaimEpoch(Math.max(cur.epoch(), observedClusterEpoch.get()));
		final RegionLeaseState claimed = cur.claimActive(next, localNodeId, localDc);
		if (!lease.compareAndSet(cur, claimed)) {
			return false;
		}
		observedClusterEpoch.set(next);
		epochConfirmed.set(true);
		persist(claimed);
		return true;
	}

	@VisibleForTesting
	public void fenceToHold() {
		final RegionLeaseState cur = lease.get();
		final RegionLeaseState fenced = cur.fenceToHold();
		lease.set(fenced);
		epochConfirmed.set(true);
		persist(fenced);
	}

	@VisibleForTesting
	public void confirmEpoch() {
		epochConfirmed.set(true);
	}

	@VisibleForTesting
	public void forceState(RegionLeaseState state) {
		Objects.requireNonNull(state, "state");
		lease.set(state);
		observedClusterEpoch.set(state.epoch());
		epochConfirmed.set(true);
		persist(state);
	}

	@VisibleForTesting
	public void forcePeerLinkSilenceMs(long silenceMs) {
		final long nanos = System.nanoTime() - Math.max(0L, silenceMs) * 1_000_000L;
		lastActivePeerLinkNanos.set(nanos);
	}

	private void persist(RegionLeaseState state) {
		if (store == null) {
			return;
		}
		try {
			store.store(state);
		} catch (IOException ex) {
			throw new IllegalStateException("region epoch persist failed", ex);
		}
	}

	@Override
	public void close() throws IOException {
		if (store != null) {
			store.close();
		}
	}
}