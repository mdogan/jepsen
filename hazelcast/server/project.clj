(defproject jepsen.hazelcast-server "0.1.0-SNAPSHOT"
  :description "A basic Hazelcast server"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :source-paths ["src"]
  :java-source-paths ["java"]
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/tools.cli "0.4.1"]
                 [org.clojure/tools.logging "0.4.1"]
                 [spootnik/unilog "0.7.13"]
                 [com.hazelcast/hazelcast-enterprise "4.0-SNAPSHOT"]]
  :profiles {:uberjar {:uberjar-name "hazelcast-server.jar"}}
  :main jepsen.hazelcast-server
  :aot [jepsen.hazelcast-server]
  :repositories {"hazelcast snapshot" "https://repository.hazelcast.com/snapshot/"
                 "hazelcast release" "https://repository.hazelcast.com/release/"})
