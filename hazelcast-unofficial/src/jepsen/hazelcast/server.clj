(ns jepsen.hazelcast.server
  (:require [clojure.tools.logging :refer :all]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jepsen [db :as db]
             [core :as core]
             [control :as c]
             [util :refer [timeout]]]
            [jepsen.control.util :as cu]
            [jepsen.control.net :as net]
            [jepsen.os.debian :as debian]
            ))

(def dir "/hazelcap")
(def hz-jar "hazelcast.jar")
(def log4j-version "1.2.17")
(def log4j-jar (str "log4j-" log4j-version ".jar"))

(def maven-snapshot-url
  "https://oss.sonatype.org/service/local/artifact/maven/content?r=snapshots&g=com.hazelcast&a=hazelcast&v=")
(def maven-url "https://repo1.maven.org/maven2/com/hazelcast/hazelcast/")

(defn prepare-members
  "Prepare members section"
  [nodes]
  (str/join
    (map (fn [node] (str "<member>" (name node) ":5701</member>")) nodes)))

(defn prepare-config
  "Replace server addresses"
  [test node]
  (let [nodes (:nodes test)]
    (-> "hazelcast.xml"
        io/resource
        slurp
        (str/replace #"<!-- PUBLIC-ADDRESS -->" (name node))
        (str/replace #"<!-- MEMBERS -->" (prepare-members nodes))
        (str/replace #"<!-- INTERFACE -->" (net/local-ip)))))


(defn fetch-jars
  "fetch jars from repo and returns the classpath"
  [version node]
  (info node "Fetching Hazelcast " version)
  (if (str/ends-with? version "SNAPSHOT")
    (c/exec :wget (str maven-snapshot-url version) :-O hz-jar)
    (c/exec :wget (str maven-url version "/hazelcast-" version ".jar") :-O hz-jar))

  (when-not (cu/exists? log4j-jar)
    (info node "Fetching " log4j-jar)
    (c/exec :wget (str "https://repo1.maven.org/maven2/log4j/log4j/" log4j-version "/" log4j-jar)))

  (info node "Fetching jars done"))

(defn classpath!
  "returns classpath"
  []
  (str hz-jar ":" log4j-jar ":."))

(defn start!
  "start hazelcast node"
  [node]
  (let [classpath (classpath!)]
    (info node "Starting Hazelcast with classpath: " classpath)
    (cu/start-daemon! {:logfile "hazelcast.log" :pidfile "hazelcast.pid" :chdir dir}
                      "/usr/bin/java"
                      "-server" "-Xms2G" "-Xmx2G"
                      "-cp" classpath
                      "com.hazelcast.core.server.StartServer")))

(defn start
  "start hazelcast node"
  [node]
  (c/su (c/cd dir (start! node))))

(defn stop
  "stops hazelcast node"
  [node]
  (info node "Stopping Hazelcast")
  (c/su (c/cd dir (cu/stop-daemon! "hazelcast.pid")))
  )

(defn db
  "Hazelcast DB for a particular version."
  [version]
  (reify db/DB
    (setup! [_ test node]

      (debian/install-jdk8!)
      (info node "JDK8 installed")

      (c/su
        (c/exec :rm :-rf dir)
        (c/exec :mkdir :-p dir)
        (c/cd dir
              (info node "Uploading hazelcast.xml & log4j.properties")
              (c/exec :echo (prepare-config test node) :> "hazelcast.xml")
              (c/exec :echo (slurp (io/resource "hazelcast-log4j.properties")) :> "log4j.properties")
              (c/exec :rm :-rf "hazelcast.log")
              (fetch-jars version node)
              (start! node)))

      (core/synchronize test)
      (info node "Hazelcast is ready"))

    (teardown! [_ test node]
      (stop node))


    db/LogFiles
    (log-files [_ test node]
      [(str dir "/hazelcast.log")])))


