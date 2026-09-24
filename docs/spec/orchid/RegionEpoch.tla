---------------------------- MODULE RegionEpoch ----------------------------
\* Multi-DC region epoch fencing: at most one Active writer per epoch.
\* Safety: fenced reject when localEpoch < clusterEpoch; claim advances epoch.
\* Run with TLC alongside OrchidLogMultiDc (informal companion; not wired into run-tlc by default).
EXTENDS Naturals, FiniteSets, TLC

CONSTANTS Nodes, MaxEpoch

VARIABLES
  role,          \* [node -> {"Active","Hold","Witness"}]
  epoch,         \* [node -> 1..MaxEpoch]
  clusterEpoch   \* observed max epoch

TypeOK ==
  /\ role \in [Nodes -> {"Active","Hold","Witness"}]
  /\ epoch \in [Nodes -> 1..MaxEpoch]
  /\ clusterEpoch \in 1..MaxEpoch

ActiveNodes == {n \in Nodes : role[n] = "Active" /\ epoch[n] = clusterEpoch}

InvAtMostOneActive == Cardinality(ActiveNodes) <= 1

InvEpochMonotonic ==
  \A n \in Nodes : epoch[n] <= clusterEpoch

InvFencedReject ==
  \A n \in Nodes :
    (role[n] = "Active" /\ epoch[n] < clusterEpoch) => FALSE
\* After Fence, role must be Hold — modeled by next-state Fence below.

Init ==
  /\ role = [n \in Nodes |-> IF n = CHOOSE x \in Nodes : TRUE THEN "Active" ELSE "Hold"]
  /\ epoch = [n \in Nodes |-> 1]
  /\ clusterEpoch = 1

Fence(n) ==
  /\ role[n] = "Active"
  /\ epoch[n] < clusterEpoch
  /\ role' = [role EXCEPT ![n] = "Hold"]
  /\ UNCHANGED <<epoch, clusterEpoch>>

Claim(n) ==
  /\ role[n] \in {"Hold", "Witness"}
  /\ clusterEpoch < MaxEpoch
  /\ LET e == clusterEpoch + 1 IN
       /\ role' = [m \in Nodes |-> IF m = n THEN "Active" ELSE
                    IF role[m] = "Active" THEN "Hold" ELSE role[m]]
       /\ epoch' = [epoch EXCEPT ![n] = e]
       /\ clusterEpoch' = e

Next == \E n \in Nodes : Fence(n) \/ Claim(n)

Spec == Init /\ [][Next]_<<role, epoch, clusterEpoch>>

THEOREM Spec => [](TypeOK /\ InvAtMostOneActive /\ InvEpochMonotonic)
=============================================================================