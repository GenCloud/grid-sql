(ns jamoa-jepsen.db
  "DB lifecycle: Compose cluster is managed outside Jepsen (docker compose up).
   This DB record is a no-op setup/teardown so control can attach to running nodes."
  (:require [clojure.tools.logging :refer [info]]
            [jepsen.db :as db]
            [jepsen.os :as os]))

(def noop-os
  (reify os/OS
    (setup! [_ _test _node] nil)
    (teardown! [_ _test _node] nil)))

(defn db
  []
  (reify db/DB
    (setup! [_ test node]
      (info "jamoa db setup (external compose) node=" node "nodes=" (:nodes test)))
    (teardown! [_ test node]
      (info "jamoa db teardown node=" node))))
