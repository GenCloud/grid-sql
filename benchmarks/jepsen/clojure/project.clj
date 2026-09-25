(defproject jamoa-jepsen "0.1.0-SNAPSHOT"
  :description "External Jepsen harness for jamoa-grid-cache (ORCHID) via grid:// SQL client"
  :url "https://github.com/genfork/jamoa-grid-cache"
  :license {:name "Proprietary"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [jepsen "0.3.5"]
                 [elle "0.2.1"]
                 [clj-http "3.12.3"]
                 [cheshire "5.12.0"]
                 ;; Prerequisite: mvn -pl grid-sql-client -am install -DskipTests
                 [org.genfork/grid-sql-client "1.0"]]
  :main jamoa-jepsen.core
  :jvm-opts ["-Djava.awt.headless=true" "--enable-preview"]
  :profiles {:uberjar {:aot :all}})
