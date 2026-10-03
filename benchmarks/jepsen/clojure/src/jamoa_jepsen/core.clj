(ns jamoa-jepsen.core
  "External Jepsen entry: register (Knossos) + append (Elle list-append)."
  (:require [elle.list-append :as la]
            [jamoa-jepsen.client :as client]
            [jamoa-jepsen.db :as db]
            [jamoa-jepsen.nemesis :as nem]
            [jepsen [checker :as checker]
                    [cli :as cli]
                    [generator :as gen]
                    [nemesis :as jnem]
                    [tests :as tests]]
            [jepsen.checker.timeline :as timeline]
            [knossos.model :as model])
  (:gen-class))

;; Separate keys from register workload (key 1) so Knossos writes do not pollute Elle lists.
(def ^:private append-keys-default (vec (range 2 17)))
;; Join/shards: keys beyond bootstrap seed (2..16) — ensureParent creates cross-shard parents.
(def ^:private append-keys-join (vec (range 2 33)))

(defn- append-keys-for-run
  []
  (if (= "1" (System/getenv "JEPSEN_JOIN_SHARDS"))
    append-keys-join
    append-keys-default))

(def cli-opts
  [["-w" "--workload NAME" "register | append | join"
    :default "register"]
   ["-r" "--rate HZ" "Approximate op rate"
    :default 10
    :parse-fn #(Double/parseDouble %)]
   ["-t" "--time-limit SECONDS"
    :default 60
    :parse-fn #(Long/parseLong %)]
   [nil "--no-nemesis" "Disable partition/kill (latency baseline)"
    :id :no-nemesis
    :default false]])

(defn register-gen
  [opts]
  (->> (gen/mix [(repeat {:f :read})
                 (map (fn [v] {:f :write :value (str v)}) (range))])
       (gen/stagger (/ (:rate opts)))))

(defn append-gen
  "Elle list-append txn mops across append keys (space-separated SQL tokens).
  Mixes single-mop and multi-mop txns (append+read on distinct keys) under SQL TX.
  Append tokens share one counter — Elle requires unique append values."
  [opts]
  (let [append-keys (append-keys-for-run)
        key-cycle (cycle append-keys)
        pair-cycle (cycle (map vector append-keys (rest (cycle append-keys))))
        n (atom -1)
        tok! (fn [] (str "t" (swap! n inc)))]
    (->> (gen/mix [(map (fn [k] {:f :txn :value [[:r k nil]]}) key-cycle)
                   (map (fn [k] {:f :txn :value [[:append k (tok!)]]}) key-cycle)
                   (map (fn [[k1 k2]]
                          {:f :txn :value [[:append k1 (tok!)] [:r k2 nil]]})
                        pair-cycle)])
         (gen/stagger (/ (:rate opts))))))

(defn append-checker
  "Elle list-append without Graphviz plots (control image may lack `dot`)."
  []
  (reify checker/Checker
    (check [_this _test history _opts]
      (la/check {:consistency-models [:strict-serializable]}
                history))))

(defn workload-checker
  [workload]
  (if (= "append" workload)
    (checker/compose
      {:elle     (append-checker)
       :timeline (timeline/html)})
    (checker/compose
      {:linear   (checker/linearizable
                   {:model (model/cas-register)
                    ;; WGL explodes memory on Multi-DC histories with many :info ops.
                    :algorithm :linear})
       :timeline (timeline/html)})))

(defn- nemesis-schedule
  [opts]
  (if (:no-nemesis opts)
    ;; Keep gen/nemesis shape but never fire faults.
    (gen/sleep (:time-limit opts))
    (let [multidc? (= "1" (System/getenv "JEPSEN_MULTIDC"))
          unclean? (= "1" (System/getenv "JEPSEN_UNCLEAN_REVIVE"))
          swarm? (= "1" (System/getenv "JEPSEN_SWARM"))]
      (cond
        ;; Unclean first (1-DC or Multi-DC): long kill+start only — avoids :info flood.
        unclean?
        (cycle [(gen/sleep 12)
                {:type :info :f :kill-proposer}
                (gen/sleep 28)])

        ;; Swarm/cutover edge: denser bounce under multi-key TX (TL typically >=60).
        swarm?
        (cycle [(gen/sleep 5)
                {:type :info :f :start-partition}
                (gen/sleep 3)
                {:type :info :f :stop-partition}
                (gen/sleep 4)
                {:type :info :f :kill-proposer}
                (gen/sleep 6)
                {:type :info :f :swarm-bounce}
                (gen/sleep 5)
                {:type :info :f :swarm-bounce}
                (gen/sleep 8)])

        multidc?
        ;; Full Multi-DC chaos: DC-link partition + kill-voter + whole DC-A kill/revive.
        (cycle [(gen/sleep 10)
                {:type :info :f :start-partition}
                (gen/sleep 4)
                {:type :info :f :stop-partition}
                (gen/sleep 12)
                {:type :info :f :kill-proposer}
                (gen/sleep 10)
                {:type :info :f :kill-dc-a}
                (gen/sleep 12)
                {:type :info :f :revive-dc-a}
                (gen/sleep 10)])

        :else
        (cycle [(gen/sleep 8)
                {:type :info :f :start-partition}
                (gen/sleep 5)
                {:type :info :f :stop-partition}
                (gen/sleep 8)
                {:type :info :f :kill-proposer}
                (gen/sleep 5)
                {:type :info :f :kill-dc-a}
                (gen/sleep 12)
                {:type :info :f :revive-dc-a}
                (gen/sleep 8)])))))

(defn- test-nodes
  "Compose node ids from JEPSEN_NODES (comma-separated), else 1-DC n1..n3."
  []
  (let [env (System/getenv "JEPSEN_NODES")]
    (if (and env (seq env))
      (vec (.split ^String env ","))
      ["n1" "n2" "n3"])))

(defn- append-style-workload?
  [workload]
  (or (= "append" workload) (= "join" workload)))

(defn jamoa-test
  [opts]
  (let [workload (:workload opts)
        no-nem? (:no-nemesis opts)
        nodes (test-nodes)
        multidc? (= "1" (System/getenv "JEPSEN_MULTIDC"))
        unclean? (= "1" (System/getenv "JEPSEN_UNCLEAN_REVIVE"))
        swarm? (= "1" (System/getenv "JEPSEN_SWARM"))
        append-style? (append-style-workload? workload)
        ;; Register+Knossos: concurrency 1 whenever sticky-only routing can yield mid-write
        ;; :info :connect (partition / kill / unclean). Append keeps higher conc for Elle.
        conc (cond
               (= "register" workload) 1
               multidc? 2
               unclean? 3
               swarm? 4
               :else 5)
        rate (cond
               multidc? (min (:rate opts) 5.0)
               (= "register" workload) (min (:rate opts) 5.0)
               :else (:rate opts))
        opts (assoc opts :rate rate)
        checker-key (if append-style? "append" workload)]
    (merge tests/noop-test
           opts
           {:name      (str "jamoa-orchid-" workload
                            (when swarm? "-swarm")
                            (when no-nem? "-nochao"))
            ;; HTTP client + docker CLI nemesis — no SSH to DB nodes.
            :ssh       {:dummy? true}
            :os        db/noop-os
            :db        (db/db)
            :client    (client/client)
            :nemesis   (if no-nem? jnem/noop (nem/compose-nemesis))
            :concurrency conc
            :generator (->> (if append-style?
                              (append-gen opts)
                              (register-gen opts))
                            (gen/nemesis (nemesis-schedule opts))
                            (gen/time-limit (:time-limit opts)))
            :checker   (workload-checker checker-key)
            ;; 1-DC n1..n3 or Multi-DC a1..b2 via JEPSEN_NODES.
            :nodes     nodes})))

(defn -main
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:opt-spec cli-opts
                                         :test-fn  jamoa-test})
                   (cli/serve-cmd))
            args))