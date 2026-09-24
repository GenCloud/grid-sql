(ns jamoa-jepsen.nemesis
  "Nemesis ops that shell out to compose helpers (partition / kill proposer)."
  (:require [clojure.java.shell :as shell]
            [clojure.tools.logging :refer [info warn]]
            [jamoa-jepsen.client :as sql-client]
            [jepsen.nemesis :as nemesis]))

(defn- script-root
  []
  (or (System/getenv "JEPSEN_SCRIPTS")
      "/jepsen/jamoa/../scripts"))

(defn- sh!
  [& args]
  (let [r (apply shell/sh args)]
    (when-not (zero? (:exit r))
      (warn "nemesis cmd failed" args (:err r) (:out r)))
    r))

(defn- multidc?
  []
  (= "1" (System/getenv "JEPSEN_MULTIDC")))

(defn compose-nemesis
  []
  (reify nemesis/Nemesis
    (setup! [this _test] this)
    (invoke! [_ _test op]
      (let [scripts (script-root)
            mdc? (multidc?)]
        (case (:f op)
          :start-partition
          (if mdc?
            (do (info "nemesis multi-dc DC-link partition")
                (sh! "bash" (str scripts "/nemesis-dc-link.sh") "isolate")
                (assoc op :type :info :value :isolated-dc-link))
            (do (info "nemesis partition isolate n3")
                (sh! "bash" (str scripts "/nemesis-partition.sh") "isolate" "n3")
                (assoc op :type :info :value :isolated-n3)))

          :stop-partition
          (if mdc?
            (do (info "nemesis multi-dc DC-link heal")
                (sh! "bash" (str scripts "/nemesis-dc-link.sh") "heal")
                (assoc op :type :info :value :healed-dc-link))
            (do (info "nemesis heal")
                (sh! "bash" (str scripts "/nemesis-partition.sh") "heal")
                (assoc op :type :info :value :healed)))

          :kill-proposer
          (if mdc?
            (do (info "nemesis multi-dc kill follower/voter (see MULTIDC_MODE)")
                (sh! "bash" (str scripts "/nemesis-dc-link.sh") "kill-voter")
                (assoc op :type :info :value :killed-multidc-target))
            (do (info "nemesis kill proposer")
                (sh! "bash" (str scripts "/nemesis-kill-proposer.sh")
                     (or (sql-client/current-proposer) ""))
                (assoc op :type :info :value :killed-proposer)))

          :kill-dc-a
          (if mdc?
            (do (info "nemesis multi-dc kill whole DC-A (leave down)")
                (sh! "bash" (str scripts "/nemesis-dc-link.sh") "kill-dc-a")
                (assoc op :type :info :value :killed-dc-a))
            (assoc op :type :info :value :kill-dc-a-skipped-1dc))

          :revive-dc-a
          (if mdc?
            (do (info "nemesis multi-dc revive DC-A (epoch fence)")
                (sh! "bash" (str scripts "/nemesis-dc-link.sh") "revive-dc-a")
                (assoc op :type :info :value :revived-dc-a))
            (assoc op :type :info :value :revive-dc-a-skipped-1dc))

          (assoc op :type :info :value :unknown))))
    (teardown! [_ _test]
      (let [scripts (script-root)]
        (if (multidc?)
          (sh! "bash" (str scripts "/nemesis-dc-link.sh") "heal")
          (sh! "bash" (str scripts "/nemesis-partition.sh") "heal"))))))
