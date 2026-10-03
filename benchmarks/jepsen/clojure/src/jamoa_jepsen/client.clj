(ns jamoa-jepsen.client
  "Jepsen client via grid-sql-client ConnectionFactory (grid:// sticky proposer).
  Sticky phase-ranked proposer via AUTH/ERROR wire ServerMeta.
  Register/append/txn ops go over SQL TCP — not /jepsen/register HTTP."
  (:require [clojure.string :as str]
            [clojure.tools.logging :refer [info warn]]
            [jepsen.client :as client])
  (:import (java.net ConnectException SocketTimeoutException)
           (org.genfork.grid.sql.client ServerMeta)
           (org.genfork.grid.sql.jepsen JepsenSqlClient)))

(def ^:private default-sql-ports
  {"n1" 15432
   "n2" 15433
   "n3" 15434})

;; Pre-generator settle: wait for sticky/promoteHint writer before ops.
(def ^:private proposer-wait-ms
  (let [env (System/getenv "JEPSEN_PROPOSER_WAIT_MS")]
    (if (and env (seq env)) (Long/parseLong env) 120000)))
(def ^:private proposer-backoff-ms
  (let [env (System/getenv "JEPSEN_PROPOSER_BACKOFF_MS")]
    (if (and env (seq env)) (Long/parseLong env) 500)))

(def ^:private sticky-proposer
  "Atom holding last known writer-eligible node id (string) or nil."
  (atom nil))

(def ^:private no-proposer-count
  "Diagnostic counter for :no-proposer fails (RESULTS notes)."
  (atom 0))

(defn- port-map
  [env-key defaults]
  (let [env (System/getenv env-key)]
    (if (and env (seq env))
      (let [ports (map #(Integer/parseInt %) (.split ^String env ","))
            nodes (.split ^String (or (System/getenv "JEPSEN_NODES") "n1,n2,n3") ",")]
        (zipmap nodes ports))
      defaults)))

(defn- sql-port-for [node]
  (get (port-map "JEPSEN_SQL_PORTS" default-sql-ports) node 15432))

(defn- all-nodes
  []
  (vec (.split ^String (or (System/getenv "JEPSEN_NODES") "n1,n2,n3") ",")))

(defn- sql-host
  [node]
  (let [override (System/getenv "JEPSEN_SQL_HOST")]
    (cond
      override override
      (= (System/getenv "JEPSEN_USE_LOCALHOST") "1") "127.0.0.1"
      :else node)))

(defn- auth-prefix
  []
  (let [user (or (System/getenv "JEPSEN_SQL_USER") "grid")
        pass (or (System/getenv "JEPSEN_SQL_PASSWORD") "grid")]
    (if (str/blank? user)
      ""
      (str user (when-not (str/blank? pass) (str ":" pass)) "@"))))

(defn- single-host-grid-url
  "Pin SQL TCP to one node so sticky proposer routing is real (not multi-host fan-in)."
  [node]
  (let [schema (or (System/getenv "JEPSEN_SQL_SCHEMA") "public")]
    (str "grid://" (auth-prefix) (sql-host node) ":" (sql-port-for node) "/" schema
         "?maxConnections=1&maxTxContexts=64")))

(defn- grid-url
  "Always single-host for the chosen node. Multi-host JEPSEN_GRID_URL would let Java sticky
  land on learners / lagging followers and break linearizability under ASYNC_SHIP."
  [node]
  (single-host-grid-url node))

(defn- orchid-not-synced?
  [result]
  (let [err (:error result)]
    (or (= :orchid-not-synced err)
        (and (sequential? err)
             (= :orchid-not-synced (keyword (first err))))
        (and (sequential? err)
             (= "orchid-not-synced" (str (first err)))))))

(defn- connect-fail?
  "Only definitive pre-flight connect :fail may sticky-retry (never :info connect)."
  [result]
  (and (= :fail (:type result)) (= :connect (:error result))))

(defn- connect-miss?
  "Register/Knossos: retry on :fail or :info :connect (single-key write; avoids Knossos blow-up)."
  [result]
  (and (#{:fail :info} (:type result)) (= :connect (:error result))))

(declare client-for! close-client!)

(defn- writer-eligible?
  [clients node]
  (try
    (let [^JepsenSqlClient c (client-for! clients node)]
      (.writerEligible c))
    (catch Exception _
      (close-client! clients node)
      false)))

(defn- wire-meta
  [clients node]
  (try
    (let [^JepsenSqlClient c (client-for! clients node)
          eligible (.writerEligible c)
          ^ServerMeta meta (.lastServerMeta c)]
      {:nodeId (.nodeId meta)
       :writerEligible eligible
       :promoteHint (.promoteHint meta)})
    (catch Exception _
      (close-client! clients node)
      nil)))

(defn- remember-proposer! [node] (reset! sticky-proposer node))
(defn- clear-proposer! [] (reset! sticky-proposer nil))

(defn current-proposer
  "Last proposer selected from SQL wire ServerMeta, for nemesis targeting."
  []
  @sticky-proposer)

(defn- discover-proposer
  "Pin all workers to sticky (if still eligible) or cluster promoteHint.
  Never falls back to first writerEligible — that dual-pins after failover flaps (G0)."
  [clients]
  (let [sticky @sticky-proposer]
    (when (and sticky (not (writer-eligible? clients sticky)))
      (clear-proposer!)))
  (or (let [sticky @sticky-proposer]
        (when (and sticky (writer-eligible? clients sticky))
          sticky))
      (let [hint (some (fn [n]
                         (when-let [meta (wire-meta clients n)]
                           (let [p (:promoteHint meta)]
                             (when (and (seq p) (writer-eligible? clients (str p)))
                               (str p)))))
                       (all-nodes))]
        (when hint
          (remember-proposer! hint)
          hint))))

(defn wait-for-proposer!
  "Pre-generator settle: retry discover-proposer until deadline (no assigned-node fallback)."
  [clients]
  (let [deadline (+ (System/currentTimeMillis) proposer-wait-ms)]
    (loop [attempt 0]
      (if-let [p (discover-proposer clients)]
        (do (info "wait-for-proposer: ready node=" p "attempt=" attempt)
            p)
        (if (< (System/currentTimeMillis) deadline)
          (do (when (zero? (mod attempt 10))
                (info "wait-for-proposer: still nil attempt=" attempt
                      "meta=" (mapv #(wire-meta clients %) (all-nodes))))
              (Thread/sleep proposer-backoff-ms)
              (recur (inc attempt)))
          (do (warn "wait-for-proposer: timed out after ms=" proposer-wait-ms
                    "meta=" (mapv #(wire-meta clients %) (all-nodes)))
              nil))))))

(defn no-proposer-count-snapshot
  "Expose diagnostic counter for harness RESULTS notes."
  []
  @no-proposer-count)

(defn- txn-has-append?
  "True when the Jepsen :txn value contains an :append mop (mutative)."
  [value]
  (some (fn [mop]
          (and (sequential? mop)
               (let [op (first mop)]
                 (or (= :append op) (= "append" (str op))))))
        (if (sequential? value) value [])))

(defn- keywordize-type
  [t]
  (cond
    (keyword? t) t
    (= "ok" (str t)) :ok
    (= "fail" (str t)) :fail
    (= "info" (str t)) :info
    :else (keyword (str t))))

(defn- ->clj
  "Convert Java lists/maps from JepsenSqlClient into Clojure data (keywords for mop ops)."
  [x]
  (cond
    (nil? x) nil
    (instance? java.util.Map x)
    (into {} (map (fn [e]
                    (let [k (.getKey ^java.util.Map$Entry e)
                          v (.getValue ^java.util.Map$Entry e)]
                      [(if (string? k) (keyword k) k) (->clj v)]))
                  (.entrySet ^java.util.Map x)))
    (instance? java.util.List x)
    (mapv (fn [item]
            (let [c (->clj item)]
              (cond
                (and (string? c) (str/starts-with? c ":")) (keyword (subs c 1))
                (and (string? c) (#{"r" "append"} c)) (keyword c)
                :else c)))
          ^java.util.List x)
    :else x))

(defn- java-result->clj
  [^java.util.Map m]
  (when m
    (let [type (keywordize-type (.get m "type"))
          value (->clj (.get m "value"))
          error (.get m "error")
          err (cond
                (nil? error) nil
                (= "connect" (str error)) :connect
                (= "timeout" (str error)) :timeout
                (instance? java.util.List error)
                (let [xs (mapv ->clj error)]
                  (if (= "orchid-not-synced" (str (first xs)))
                    (into [:orchid-not-synced] (rest xs))
                    xs))
                :else error)]
      (cond-> {:type type}
        (contains? (set (.keySet m)) "value") (assoc :value value)
        err (assoc :error err)))))

(defn- client-for!
  "Return (or open) JepsenSqlClient for node; stored on record atom map."
  [clients node]
  (or (get @clients node)
      (let [c (JepsenSqlClient. (grid-url node))]
        (swap! clients assoc node c)
        c)))

(defn- close-client!
  [clients node]
  (when-let [^JepsenSqlClient c (get @clients node)]
    (try (.close c) (catch Exception _))
    (swap! clients dissoc node)))

(defn- invoke-sql!
  [clients node f value]
  (try
    (let [^JepsenSqlClient c (client-for! clients node)
          ;; Jepsen keywords → Java strings
          f-str (name f)
          ;; txn mop values: convert Clojure data for Java
          java-val (cond
                     (nil? value) nil
                     (= :txn f)
                     (java.util.ArrayList.
                      (map (fn [mop]
                             (java.util.ArrayList.
                              (map (fn [x]
                                     (if (keyword? x) (name x) x))
                                   mop)))
                           value))
                     :else (if (keyword? value) (name value) value))
          raw (.invoke c f-str java-val)]
      (java-result->clj raw))
    (catch SocketTimeoutException _
      {:type :info :error :timeout})
    (catch ConnectException _
      ;; Pre-flight / socket never established — definite :fail so sticky can rediscover.
      ;; Mid-flight close is classified inside JepsenSqlClient by afterDispatch (:info only
      ;; when a mutate may already have left the client).
      (close-client! clients node)
      {:type :fail :error :connect})
    (catch Exception e
      (warn e "sql client error")
      (let [connectish? (or (instance? java.net.ConnectException (ex-cause e))
                            (str/includes? (or (.getMessage e) "") "connect"))]
        (when connectish?
          (close-client! clients node))
        (if connectish?
          {:type :fail :error :connect}
          {:type :info :error (.getMessage e)})))))

(defn- no-proposer-fail
  "Elle-safe fail when sticky/promoteHint has no writer-eligible node."
  [clients value]
  (swap! no-proposer-count inc)
  ;; Stale channels: close all before next discover (fresh AUTH / ServerMeta).
  (doseq [n (all-nodes)]
    (close-client! clients n))
  (clear-proposer!)
  (warn "no-proposer sticky=" @sticky-proposer
        "count=" @no-proposer-count
        "meta=" (mapv (fn [n]
                        (let [m (wire-meta clients n)]
                          {:node n
                           :nodeId (:nodeId m)
                           :writerEligible (:writerEligible m)
                           :promoteHint (:promoteHint m)}))
                      (all-nodes)))
  {:type :fail :error :no-proposer :value value})

(defn- invoke-with-sticky!
  "Append/Elle path: route only to sticky/promoteHint — never assigned-node fallback
  (cross-worker pins caused G-nonadjacent after unclean-revive).
  No transparent read retry on orchid fence (stale mid-history :ok).
  Mutative orchid fence stays :fail. Connect-fail may retry once via discover only."
  [clients _assigned-node f value]
  (let [primary (discover-proposer clients)]
    (if (nil? primary)
      (no-proposer-fail clients value)
      (let [first-try (invoke-sql! clients primary f value)]
        (cond
          (= :ok (:type first-try))
          (do (remember-proposer! primary) first-try)

          (connect-fail? first-try)
          (do
            (clear-proposer!)
            (close-client! clients primary)
            (let [second-node (discover-proposer clients)
                  second-try (when (and second-node (not= second-node primary))
                               (invoke-sql! clients second-node f value))]
              (cond
                (nil? second-try) first-try
                (= :ok (:type second-try))
                (do (remember-proposer! second-node) second-try)
                :else second-try)))

          (orchid-not-synced? first-try)
          (do
            (close-client! clients primary)
            (when-not (writer-eligible? clients primary)
              (clear-proposer!))
            (assoc first-try :type :fail))

          :else first-try)))))

(defn- invoke-register!
  "Register/Knossos path: sticky first, then assigned-node fallback on connect miss.
  Safe for single-key CAS (Elle G-nonadjacent is append-only)."
  [clients assigned-node f value]
  (let [primary (discover-proposer clients)
        target (or primary assigned-node)
        first-try (invoke-sql! clients target f value)]
    (cond
      (= :ok (:type first-try))
      (do (when primary (remember-proposer! primary)) first-try)

      (connect-miss? first-try)
      (do
        (clear-proposer!)
        (close-client! clients target)
        (let [second (or (discover-proposer clients)
                         (when (not= assigned-node target) assigned-node))
              second-try (when (and second (not= second target))
                           (invoke-sql! clients second f value))]
          (cond
            (nil? second-try) first-try
            (= :ok (:type second-try))
            (do (remember-proposer! second) second-try)
            ;; Prefer definitive :fail over lingering :info connect for Knossos.
            (connect-fail? second-try) second-try
            :else first-try)))

      (orchid-not-synced? first-try)
      (do
        (close-client! clients target)
        (when-not (writer-eligible? clients target)
          (clear-proposer!))
        (assoc first-try :type :fail))

      :else first-try)))

(defrecord SqlClient [node clients]
  client/Client
  (open! [_ _test node]
    (SqlClient. node (atom {})))
  (setup! [this _]
    (wait-for-proposer! clients)
    this)
  (invoke! [this _test op]
    (let [assigned (or node "n1")
          f (:f op)
          ;; :txn/:append → sticky-only (Elle). :read/:write → register fallback (Knossos).
          result (if (or (= :txn f) (= :append f))
                   (invoke-with-sticky! clients assigned f (:value op))
                   (invoke-register! clients assigned f (:value op)))]
      (merge op (select-keys result [:type :value :error]))))
  (teardown! [_ _]
    (info "jepsen-diag no-proposer-count=" (no-proposer-count-snapshot)
          "sticky=" @sticky-proposer))
  (close! [_ _]
    (info "jepsen-diag no-proposer-count=" (no-proposer-count-snapshot))
    (doseq [[n ^JepsenSqlClient c] @clients]
      (try (.close c) (catch Exception _)))
    (reset! clients {})))

(defn client
  []
  (SqlClient. nil (atom {})))
