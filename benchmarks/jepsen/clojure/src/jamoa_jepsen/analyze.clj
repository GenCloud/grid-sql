(ns jamoa-jepsen.analyze
  "Offline Knossos check for a saved register history.edn (escape Docker RAM pressure)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [jepsen.checker :as checker]
            [jepsen.checker.timeline :as timeline]
            [knossos.model :as model])
  (:gen-class))

(defn- load-history
  [path]
  (with-open [r (io/reader path)]
    (let [pbr (java.io.PushbackReader. r)]
      (loop [acc []]
        (let [form (edn/read {:eof ::eof} pbr)]
          (if (= form ::eof)
            acc
            (recur (conj acc form))))))))

(defn -main
  [& args]
  (let [path (or (first args) (System/getenv "HISTORY"))
        _ (when-not (seq path)
            (binding [*out* *err*]
              (println "usage: lein run -m jamoa-jepsen.analyze <history.edn>")
              (System/exit 2)))
        history (load-history path)
        chk (checker/compose
              {:linear (checker/linearizable
                         {:model (model/cas-register)
                          :algorithm :linear})
               :timeline (timeline/html)})
        test {:name "offline-register" :concurrency 3}
        result (checker/check chk test history {})]
    (prn result)
    (flush)
    (System/exit (if (true? (:valid? result)) 0 1))))