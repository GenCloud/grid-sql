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
(def ^:private append-keys (vec (range 2 17)))

(def cli-opts
  [["-w" "--workload NAME" "register | append"
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
  "Elle list-append txn mops across keys 2..16 (space-separated SQL tokens)."
  [opts]
  (let [key-cycle (cycle append-keys)]
    (->> (gen/mix [(map (fn [k] {:f :txn :value [[:r k nil]]}) key-cycle)
                   (map (fn [[k v]] {:f :txn :value [[:append k (str "t" v)]]})
                        (map vector key-cycle (range)))])
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
    (let [multidc? (= "1" (System/getenv "JEPSEN_MULTIDC"))]
      ;; Multi-DC: partition + kill-voter only (no whole-DC kill) so Knossos can finish
      ;; after Elle-safe :info connect classification on in-flight writes.
      (if multidc?
        (cycle [(gen/sleep 10)
                {:type :info :f :start-partition}
                (gen/sleep 4)
                {:type :info :f :stop-partition}
                (gen/sleep 12)
                {:type :info :f :kill-proposer}
                (gen/sleep 10)])
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

(defn jamoa-test
  [opts]
  (let [workload (:workload opts)
        no-nem? (:no-nemesis opts)
        nodes (test-nodes)
        multidc? (= "1" (System/getenv "JEPSEN_MULTIDC"))
        ;; Register+Knossos: concurrency 1 under Multi-DC so :info connect writes stay analyzable.
        conc (cond
               (and multidc? (= "register" workload)) 1
               multidc? 2
               :else 5)
        rate (if multidc? (min (:rate opts) 5.0) (:rate opts))
        opts (assoc opts :rate rate)]
    (merge tests/noop-test
           opts
           {:name      (str "jamoa-orchid-" workload (when no-nem? "-nochao"))
            ;; HTTP client + docker CLI nemesis — no SSH to DB nodes.
            :ssh       {:dummy? true}
            :os        db/noop-os
            :db        (db/db)
            :client    (client/client)
            :nemesis   (if no-nem? jnem/noop (nem/compose-nemesis))
            :concurrency conc
            :generator (->> (if (= "append" workload)
                              (append-gen opts)
                              (register-gen opts))
                            (gen/nemesis (nemesis-schedule opts))
                            (gen/time-limit (:time-limit opts)))
            :checker   (workload-checker workload)
            ;; 1-DC n1..n3 or Multi-DC a1..b2 via JEPSEN_NODES.
            :nodes     nodes})))

(defn -main
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:opt-spec cli-opts
                                         :test-fn  jamoa-test})
                   (cli/serve-cmd))
            args))