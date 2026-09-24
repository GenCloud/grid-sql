---- MODULE RegionClaim ----
EXTENDS Naturals, FiniteSets, TLC

\* Minimal Active/Hold claim+fence model.
CONSTANTS Nodes, MaxEpoch

ASSUME Nodes # {} /\ MaxEpoch \in Nat /\ MaxEpoch >= 1

VARIABLES role, epoch, observed

TypeOK ==
  /\ role \in [Nodes -> {"Active", "Hold", "Witness"}]
  /\ epoch \in [Nodes -> 1..MaxEpoch]
  /\ observed \in [Nodes -> 1..MaxEpoch]

Init ==
  /\ \E a \in Nodes :
        /\ role = [n \in Nodes |-> IF n = a THEN "Active" ELSE "Hold"]
        /\ epoch = [n \in Nodes |-> 1]
        /\ observed = [n \in Nodes |-> 1]

NoActive == \A n \in Nodes : role[n] # "Active"

Claim(h) ==
  /\ role[h] \in {"Hold", "Witness"}
  /\ NoActive
  /\ observed[h] < MaxEpoch
  /\ LET next == observed[h] + 1 IN
     /\ role' = [role EXCEPT ![h] = "Active"]
     /\ epoch' = [epoch EXCEPT ![h] = next]
     /\ observed' = [n \in Nodes |-> IF observed[n] < next THEN next ELSE observed[n]]

Fence(a, peer) ==
  /\ role[a] = "Active"
  /\ epoch[peer] > observed[a]
  /\ role' = [role EXCEPT ![a] = "Hold"]
  /\ UNCHANGED epoch
  /\ observed' = [observed EXCEPT ![a] = epoch[peer]]

Observe(n, peer) ==
  /\ observed' = [observed EXCEPT ![n] =
        IF epoch[peer] > observed[n] THEN epoch[peer] ELSE observed[n]]
  /\ UNCHANGED <<role, epoch>>

Next ==
  \/ \E h \in Nodes : Claim(h)
  \/ \E a, p \in Nodes : a # p /\ Fence(a, p)
  \/ \E n, p \in Nodes : n # p /\ Observe(n, p)

Spec == Init /\ [][Next]_<<role, epoch, observed>>

AtMostOneActive == Cardinality({n \in Nodes : role[n] = "Active"}) <= 1

====