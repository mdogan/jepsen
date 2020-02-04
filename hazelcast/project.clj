(defproject jepsen.hazelcast "0.1.0-SNAPSHOT"
  :description "Jepsen tests for Hazelcast IMDG"
  :url "http://jepsen.io/"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [jepsen "0.1.18-SNAPSHOT3"]
                 [com.hazelcast/hazelcast-enterprise "4.1-SNAPSHOT"]]
  :repositories {"hazelcast snapshot" "https://repository.hazelcast.com/snapshot/"
                 "hazelcast release" "https://repository.hazelcast.com/release/"}
  :aot :all
  :main jepsen.hazelcast)
