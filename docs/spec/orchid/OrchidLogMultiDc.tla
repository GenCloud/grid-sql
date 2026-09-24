---------------------------- MODULE OrchidLogMultiDc ----------------------------
\* ORCHID multi-DC slot agreement: local Kuramoto quorum + remote digest voters.
\* Remote voters are NOT in the local reachable set (phaseCoupling=false).
\* ASYNC_SHIP product contract: write admission = LocalNodes only; remotes are
\* learners (apply/ship) and never appear in LocalNodes / RankedProposer.
\* Run: scripts/run-tlc-orchid.sh|.ps1 (runs both specs)
EXTENDS Naturals, Sequences, FiniteSets, TLC

CONSTANTS n1, n2, n3, r1, MaxSeq

LocalNodes == {n1, n2, n3}
RemoteVoters == {r1}
\* ASYNC remote learners (ship targets) — not local write-admission nodes.
AsyncLearners == {r1}
NodeOrder == <<n1, n2, n3>>

VARIABLES
  lastCommitted,
  slotDigest,
  persisted,
  proposer,
  configuredVoters,
  localReachable,
  remoteReachable,
  pendingDigest,
  remoteAcked

None == 0

Rank(n) == CHOOSE i \in DOMAIN NodeOrder : NodeOrder[i] = n

RankedProposer ==
  CHOOSE n \in localReachable : \A m \in localReachable : Rank(n) <= Rank(m)

LocalQuorum(S) == Cardinality(S) * 2 > Cardinality(configuredVoters)

RemoteDigestsMet ==
  /\ remoteReachable = RemoteVoters
  /\ remoteAcked = RemoteVoters

TypeOK ==
  /\ lastCommitted \in [LocalNodes -> 0..MaxSeq]
  /\ slotDigest \in [1..MaxSeq -> Nat]
  /\ persisted \in [1..MaxSeq -> BOOLEAN]
  /\ proposer \in LocalNodes
  /\ configuredVoters = LocalNodes
  /\ localReachable \subseteq LocalNodes
  /\ localReachable # {}
  /\ remoteReachable \subseteq RemoteVoters
  /\ pendingDigest \in Nat
  /\ remoteAcked \subseteq RemoteVoters

Init ==
  /\ lastCommitted = [n \in LocalNodes |-> 0]
  /\ slotDigest = [s \in 1..MaxSeq |-> None]
  /\ persisted = [s \in 1..MaxSeq |-> FALSE]
  /\ configuredVoters = LocalNodes
  /\ localReachable = LocalNodes
  /\ remoteReachable = RemoteVoters
  /\ proposer = RankedProposer
  /\ pendingDigest = None
  /\ remoteAcked = {}

Propose(n, digest) ==
  /\ n = RankedProposer
  /\ LocalQuorum(localReachable)
  /\ pendingDigest = None
  /\ digest \in 1..3
  /\ pendingDigest' = digest
  /\ proposer' = n
  /\ remoteAcked' = {}
  /\ UNCHANGED <<lastCommitted, slotDigest, persisted, configuredVoters,
                 localReachable, remoteReachable>>

\* Remote voter ACKs the pending digest (WAN path; independent of local R).
RemoteAck(r) ==
  /\ pendingDigest # None
  /\ r \in remoteReachable
  /\ r \notin remoteAcked
  /\ remoteAcked' = remoteAcked \cup {r}
  /\ UNCHANGED <<lastCommitted, slotDigest, persisted, proposer,
                 configuredVoters, localReachable, remoteReachable, pendingDigest>>

Commit ==
  /\ pendingDigest # None
  /\ LocalQuorum(localReachable)
  /\ RemoteDigestsMet
  /\ LET seq == lastCommitted[proposer] + 1 IN
       /\ seq \in 1..MaxSeq
       /\ slotDigest[seq] = None
       /\ persisted' = [persisted EXCEPT ![seq] = TRUE]
       /\ slotDigest' = [slotDigest EXCEPT ![seq] = pendingDigest]
       /\ lastCommitted' =
            [m \in LocalNodes |->
               IF m \in localReachable THEN seq ELSE lastCommitted[m]]
       /\ pendingDigest' = None
       /\ remoteAcked' = {}
       /\ UNCHANGED <<proposer, configuredVoters, localReachable, remoteReachable>>

PartitionLocalMinority ==
  /\ localReachable' = {n1}
  /\ UNCHANGED <<lastCommitted, slotDigest, persisted, proposer,
                 configuredVoters, remoteReachable, pendingDigest, remoteAcked>>

PartitionLocalMajority ==
  /\ localReachable' = {n1, n2}
  /\ UNCHANGED <<lastCommitted, slotDigest, persisted, proposer,
                 configuredVoters, remoteReachable, pendingDigest, remoteAcked>>

\* WAN partition: remote cannot ACK → Commit blocked even with local quorum.
PartitionRemote ==
  /\ remoteReachable' = {}
  /\ remoteAcked' = {}
  /\ UNCHANGED <<lastCommitted, slotDigest, persisted, proposer,
                 configuredVoters, localReachable, pendingDigest>>

HealLocal ==
  /\ localReachable' = configuredVoters
  /\ UNCHANGED <<lastCommitted, slotDigest, persisted, proposer,
                 configuredVoters, remoteReachable, pendingDigest, remoteAcked>>

HealRemote ==
  /\ remoteReachable' = RemoteVoters
  /\ UNCHANGED <<lastCommitted, slotDigest, persisted, proposer,
                 configuredVoters, localReachable, pendingDigest, remoteAcked>>

Next ==
  \/ \E n \in LocalNodes, d \in 1..2 : Propose(n, d)
  \/ \E r \in RemoteVoters : RemoteAck(r)
  \/ Commit
  \/ PartitionLocalMinority
  \/ PartitionLocalMajority
  \/ PartitionRemote
  \/ HealLocal
  \/ HealRemote

vars == <<lastCommitted, slotDigest, persisted, proposer, configuredVoters,
          localReachable, remoteReachable, pendingDigest, remoteAcked>>

Spec == Init /\ [][Next]_vars

SingleSlotAgreement ==
  \A s \in 1..MaxSeq :
    \A n \in LocalNodes :
      lastCommitted[n] >= s => slotDigest[s] # None

LogPrefix ==
  \A n \in LocalNodes :
    \A s \in 1..lastCommitted[n] :
      /\ slotDigest[s] # None
      /\ persisted[s] = TRUE

NoForkEq ==
  \A s \in 1..MaxSeq :
    \A a, b \in LocalNodes :
      (lastCommitted[a] >= s /\ lastCommitted[b] >= s) =>
        slotDigest[s] # None

DurableBeforeAdvance ==
  \A n \in LocalNodes :
    \A s \in 1..lastCommitted[n] :
      persisted[s] = TRUE

ConfiguredQuorumFixed ==
  configuredVoters = LocalNodes

\* Remote voters never appear in localReachable (phaseCoupling=false).
RemoteNotInLocalR ==
  remoteReachable \cap localReachable = {}

\* ASYNC / SYNC: remote learners are never local write-admission nodes (constant).
ASSUME AsyncLearnersNotLocalWriters == AsyncLearners \cap LocalNodes = {}

\* If a slot advanced, remote digests were required at commit time —
\* modelled indirectly: commit only when RemoteDigestsMet; after commit
\* remoteAcked clears. Safety: no commit without prior remote path health
\* is enforced by Commit guard; this invariant keeps remotes disjoint from R.
=============================================================================
