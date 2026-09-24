---------------------------- MODULE OrchidLog ----------------------------
\* ORCHID slot agreement (native algorithm — not Raft).
\* Run: scripts/run-tlc-orchid.sh|.ps1
EXTENDS Naturals, Sequences, FiniteSets, TLC

CONSTANTS n1, n2, n3, MaxSeq

Nodes == {n1, n2, n3}
NodeOrder == <<n1, n2, n3>>

VARIABLES
  lastCommitted,
  slotDigest,
  persisted,
  proposer,
  configuredVoters,
  reachable,
  pendingDigest

None == 0

Rank(n) == CHOOSE i \in DOMAIN NodeOrder : NodeOrder[i] = n

RankedProposer ==
  CHOOSE n \in reachable : \A m \in reachable : Rank(n) <= Rank(m)

TypeOK ==
  /\ lastCommitted \in [Nodes -> 0..MaxSeq]
  /\ slotDigest \in [1..MaxSeq -> Nat]
  /\ persisted \in [1..MaxSeq -> BOOLEAN]
  /\ proposer \in Nodes
  /\ configuredVoters = Nodes
  /\ reachable \subseteq Nodes
  /\ reachable # {}
  /\ pendingDigest \in Nat

Init ==
  /\ lastCommitted = [n \in Nodes |-> 0]
  /\ slotDigest = [s \in 1..MaxSeq |-> None]
  /\ persisted = [s \in 1..MaxSeq |-> FALSE]
  /\ configuredVoters = Nodes
  /\ reachable = Nodes
  /\ proposer = RankedProposer
  /\ pendingDigest = None

Quorum(S) == Cardinality(S) * 2 > Cardinality(configuredVoters)

Propose(n, digest) ==
  /\ n = RankedProposer
  /\ Quorum(reachable)
  /\ pendingDigest = None
  /\ digest \in 1..10
  /\ pendingDigest' = digest
  /\ proposer' = n
  /\ UNCHANGED <<lastCommitted, slotDigest, persisted, configuredVoters, reachable>>

Commit ==
  /\ pendingDigest # None
  /\ Quorum(reachable)
  /\ LET seq == lastCommitted[proposer] + 1 IN
       /\ seq \in 1..MaxSeq
       /\ slotDigest[seq] = None
       /\ persisted' = [persisted EXCEPT ![seq] = TRUE]
       /\ slotDigest' = [slotDigest EXCEPT ![seq] = pendingDigest]
       /\ lastCommitted' =
            [m \in Nodes |-> IF m \in reachable THEN seq ELSE lastCommitted[m]]
       /\ pendingDigest' = None
       /\ UNCHANGED <<proposer, configuredVoters, reachable>>

NackCompetitor(n) ==
  /\ n \in reachable
  /\ n # RankedProposer
  /\ UNCHANGED <<lastCommitted, slotDigest, persisted, proposer,
                 configuredVoters, reachable, pendingDigest>>

PartitionMajority ==
  /\ reachable' = {n1, n2}
  /\ UNCHANGED <<lastCommitted, slotDigest, persisted, proposer,
                 configuredVoters, pendingDigest>>

PartitionMinority ==
  /\ reachable' = {n1}
  /\ UNCHANGED <<lastCommitted, slotDigest, persisted, proposer,
                 configuredVoters, pendingDigest>>

ForgetPeer ==
  /\ n3 \in reachable
  /\ Cardinality(reachable) > 1
  /\ reachable' = reachable \ {n3}
  /\ UNCHANGED <<lastCommitted, slotDigest, persisted, proposer,
                 configuredVoters, pendingDigest>>

Heal ==
  /\ reachable' = configuredVoters
  /\ UNCHANGED <<lastCommitted, slotDigest, persisted, proposer,
                 configuredVoters, pendingDigest>>

Next ==
  \/ \E n \in Nodes, d \in 1..3 : Propose(n, d)
  \/ Commit
  \/ \E n \in Nodes : NackCompetitor(n)
  \/ PartitionMajority
  \/ PartitionMinority
  \/ ForgetPeer
  \/ Heal

Spec == Init /\ [][Next]_<<lastCommitted, slotDigest, persisted, proposer,
                           configuredVoters, reachable, pendingDigest>>

SingleSlotAgreement ==
  \A s \in 1..MaxSeq :
    \A n \in Nodes :
      lastCommitted[n] >= s => slotDigest[s] # None

LogPrefix ==
  \A n \in Nodes :
    \A s \in 1..lastCommitted[n] :
      /\ slotDigest[s] # None
      /\ persisted[s] = TRUE

NoForkEq ==
  \A s \in 1..MaxSeq :
    \A n1b, n2b \in Nodes :
      (lastCommitted[n1b] >= s /\ lastCommitted[n2b] >= s) =>
        slotDigest[s] # None

DurableBeforeAdvance ==
  \A n \in Nodes :
    \A s \in 1..lastCommitted[n] :
      persisted[s] = TRUE

ConfiguredQuorumFixed ==
  configuredVoters = Nodes

=============================================================================
