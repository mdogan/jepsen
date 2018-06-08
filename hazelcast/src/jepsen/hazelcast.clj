(ns jepsen.hazelcast
  (:gen-class)
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [clojure.java.shell :refer [sh]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [knossos.model :as model]
            [jepsen [checker :as checker]
                    [cli :as cli]
                    [client :as client]
                    [core :as jepsen]
                    [control :as c]
                    [db :as db]
                    [generator :as gen]
                    [independent :as independent]
                    [nemesis :as nemesis]
                    [tests :as tests]
                    [reconnect :as rc]
                    [util :as util :refer [timeout]]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.control.util :as cu]
            [jepsen.control.net :as cn]
            [jepsen.os.debian :as debian])
  (:import (java.util.concurrent TimeUnit)
           (com.hazelcast.client.config ClientConfig)
           (com.hazelcast.client HazelcastClient)
           (com.hazelcast.core HazelcastInstance)
           (com.hazelcast.core IMap)))

(def local-server-dir
  "Relative path to local server project directory"
  "server")

(def local-server-jar
  "Relative to server fat jar"
  (str local-server-dir "/target/hazelcast-server.jar"))

(def dir
  "Remote path for hazelcast stuff"
  "/opt/hazelcast")

(def jar
  "Full path to Hazelcast server jar on remote nodes."
  (str dir "/server.jar"))

(def pid-file (str dir "/server.pid"))
(def log-file (str dir "/server.log"))

(defn build-server!
  "Ensures the server jar is ready"
  [test node]
  (when (= node (jepsen/primary test))
    ; (when-not (.exists (io/file local-server-jar))
      (info "Building server")
      (let [{:keys [exit out err]} (sh "lein" "uberjar" :dir "server")]
        (info out)
        (info err)
        (info exit)
        (assert (zero? exit)))))
        ; )

(defn install!
  "Installs the server on remote nodes."
  []
  (c/cd dir
    (c/exec :mkdir :-p dir)
    (c/upload (.getCanonicalPath (io/file local-server-jar))
              jar)))

(defn start!
  "Launch hazelcast server"
  [test node]
  (c/cd dir
        (cu/start-daemon!
          {:chdir dir
           :logfile log-file
           :pidfile pid-file}
          "/usr/bin/java"
          :-jar jar
          :--members (->> (:nodes test)
                          (map cn/ip)
                          (str/join ",")))))

(defn stop!
  "Kill hazelcast server"
  [test node]
  (c/cd dir
        (c/su
          (cu/stop-daemon! pid-file))))

(defn db
  "Installs and runs hazelcast nodes"
  []
  (reify db/DB
    (setup! [_ test node]
      (build-server! test node)
      (jepsen/synchronize test)
      (debian/install-jdk8!)
      (install!)
      (start! test node)
      (Thread/sleep 15000))

    (teardown! [_ test node]
      (stop! test node)
      (c/su
        (c/exec :rm :-rf log-file pid-file)))

    db/LogFiles
    (log-files [_ test node]
      [log-file])))

(defn ^HazelcastInstance connect
  "Creates a hazelcast client for the given node."
  [node]
  (let [config (ClientConfig.)
        ; Global op timeouts
        _ (.setProperty config "hazelcast.client.heartbeat.interval" "1000")
        _ (.setProperty config "hazelcast.client.heartbeat.timeout" "5000")
        _ (.setProperty config "hazelcast.client.invocation.timeout.seconds" "5")

        net    (doto (.getNetworkConfig config)
                 ; Don't retry operations when network fails (!?)
                 (.setRedoOperation false)
                 ; Timeouts
                 (.setConnectionTimeout 5000)
                 ; Initial connection limits
                 (.setConnectionAttemptPeriod 1000)
                 ; Try reconnecting indefinitely
                 (.setConnectionAttemptLimit 0)
                 ; Don't use a local cache of the partition map
                 (.setSmartRouting false))
        _      (info :net net)
        ; Only talk to our node (the client's smart and will try to talk to
        ; everyone, but we're trying to simulate clients in different network
        ; components here)
        ; Connect to our node
        _      (.addAddress net (into-array String [node]))]
    (HazelcastClient/newHazelcastClient config)))

(defn atomic-long-id-client
  "Generates unique IDs using an AtomicLong"
  [conn atomic-long]
  (reify client/Client
    (setup! [_ test node]
      (let [conn (connect node)]
        (atomic-long-id-client conn
                               (.getAtomicLong conn "jepsen.atomic-long"))))

    (invoke! [this test op]
      (assert (= (:f op) :generate))
      (assoc op :type :ok, :value (.incrementAndGet atomic-long)))

    (teardown! [this test]
      (.shutdown conn))))


(defn create-raft-atomic-long
  "Creates a new Raft based AtomicLong"
  [client name]
  (com.hazelcast.raft.service.atomiclong.client.RaftAtomicLongProxy/create client name))

(defn raft-atomic-long-id-client
  "Generates unique IDs using a Raft based AtomicLong"
  [conn atomic-long]
  (reify client/Client
    (setup! [_ test node]
      (let [conn (connect node)]
        (raft-atomic-long-id-client conn
                   (create-raft-atomic-long conn "jepsen.atomic-long"))))

    (invoke! [this test op]
      (assert (= (:f op) :generate))
      (assoc op :type :ok, :value (.incrementAndGet atomic-long)))

    (teardown! [this test]
      (.shutdown conn))))

(defn raft-cas-register-client
  "A CAS register using a Raft based AtomicLong"
  [conn atomic-long]
  (reify client/Client
    (setup! [_ test node]
      (let [conn (connect node)]
        (raft-cas-register-client conn
                                    (create-raft-atomic-long conn "jepsen.cas-register"))))

    (invoke! [this test op]
      (case (:f op)
        :read (assoc op :type :ok, :value (.get atomic-long))
        :write (do (.set atomic-long (:value op))
                   (assoc op :type :ok))
        :cas (let [[currentV newV] (:value op)]
               (if (.compareAndSet atomic-long currentV newV)
                 (assoc op :type :ok)
                 (assoc op :type :fail :error :cas-failed)
                 ))
        )
      )

    (teardown! [this test]
      (.shutdown conn))))

(defn atomic-ref-id-client
  "Generates unique IDs using an AtomicReference"
  [conn atomic-ref]
  (reify client/Client
    (setup! [_ test node]
      (let [conn (connect node)]
        (atomic-ref-id-client conn
                              (.getAtomicReference conn "jepsen.atomic-ref"))))

    (invoke! [this test op]
      (assert (= (:f op) :generate))
      (let [v (.get atomic-ref)
            v' (inc (or v 0))]
        (if (.compareAndSet atomic-ref v v')
          (assoc op :type :ok, :value v')
          (assoc op :type :fail, :error :cas-failed))))

    (teardown! [this test]
      (.shutdown conn))))

(defn id-gen-id-client
  "Generates unique IDs using an IdGenerator"
  [conn id-gen]
  (reify client/Client
    (setup! [_ test node]
      (let [conn (connect node)]
        (id-gen-id-client conn
                          (.getIdGenerator conn "jepsen.id-gen"))))

    (invoke! [this test op]
      (assert (= (:f op) :generate))
      (assoc op :type :ok, :value (.newId id-gen)))

    (teardown! [this test]
      (.shutdown conn))))

(def queue-poll-timeout
  "How long to wait for items to become available in the queue, in ms"
  1)

(defn queue-client
  "Uses :enqueue, :dequeue, and :drain events to interact with a Hazelcast
  queue."
  ([]
   (queue-client nil nil))
  ([conn queue]
   (reify client/Client
     (setup! [_ test node]
       (let [conn (connect node)]
         (queue-client conn (.getQueue conn "jepsen.queue"))))

     (invoke! [this test op]
       (case (:f op)
         :enqueue (do (.put queue (:value op))
                      (assoc op :type :ok))
         :dequeue (if-let [v (.poll queue
                                 queue-poll-timeout TimeUnit/MILLISECONDS)]
                    (assoc op :type :ok, :value v)
                    (assoc op :type :fail, :error :empty))
         :drain   (loop [values []]
                    (if-let [v (.poll queue
                                      queue-poll-timeout TimeUnit/MILLISECONDS)]
                      (recur (conj values v))
                      (assoc op :type :ok, :value values)))))

     (teardown! [this test]
       (.shutdown conn)))))

(defn queue-gen
  "A generator for queue operations. Emits enqueues of sequential integers."
  []
  (let [next-element (atom -1)]
    (->> (gen/mix [(fn enqueue-gen [_ _]
                     {:type  :invoke
                      :f     :enqueue
                      :value (swap! next-element inc)})
                   {:type :invoke, :f :dequeue}])
         (gen/stagger 1))))

(defn queue-client-and-gens
  "Constructs a queue client and generator. Returns {:client
  ..., :generator ...}."
  []
  {:client          (queue-client)
   :generator       (queue-gen)
   :final-generator (->> {:type :invoke, :f :drain}
                         gen/once
                         gen/each)})

(defn create-raft-lock
  "Creates a new Raft based Lock"
  [client name]
  (com.hazelcast.raft.service.lock.client.RaftLockProxy/create client name))


(defn raft-lock-client
  ([] (raft-lock-client nil nil))
  ([conn lock-name]
   (reify client/Client
     (setup! [_ test node]
       (let [conn (rc/open! (rc/wrapper {:name  "hazelcast"
                                         :open  #(connect node)
                                         :close #(.shutdown %)
                                         :log?  true}))]
         (raft-lock-client conn "jepsen.lock")))

     (invoke! [this test op]
       (try
         (rc/with-conn [c conn]
           (let [lock (create-raft-lock c lock-name)]
             (case (:f op)
               :acquire (if (.tryLock lock 5000 TimeUnit/MILLISECONDS)
                          (assoc op :type :ok)
                          (assoc op :type :fail))
               :release (do (.unlock lock)
                            (assoc op :type :ok)))))
        (catch java.lang.IllegalMonitorStateException e
          (Thread/sleep 1000)
          (assoc op :type :fail, :error :not-lock-owner))
        (catch java.io.IOException e
          (Thread/sleep 1000)
          (condp re-find (.getMessage e)
            ; This indicates that the Hazelcast client doesn't have a remote
            ; peer available, and that the message was never sent.
            #"Packet is not send to owner address"
            (assoc op :type :fail, :error :client-down)

            (throw e)))))

     (teardown! [this test]
       (.shutdown conn)))))

(defn lock-client
  ([] (lock-client nil nil))
  ([conn lock-name]
   (reify client/Client
     (setup! [_ test node]
       (let [conn (rc/open! (rc/wrapper {:name  "hazelcast"
                                         :open  #(connect node)
                                         :close #(.terminate %)
                                         :log?  true}))]
         (lock-client conn "jepsen.lock")))

     (invoke! [this test op]
       (try
         (rc/with-conn [c conn]
           (let [lock (.getLock c lock-name)]
             (case (:f op)
               :acquire (if (.tryLock lock 5000 TimeUnit/MILLISECONDS)
                          (assoc op :type :ok)
                          (assoc op :type :fail))
               :release (do (.unlock lock)
                            (assoc op :type :ok)))))
        (catch com.hazelcast.quorum.QuorumException e
          (Thread/sleep 1000)
          (assoc op :type :fail, :error :quorum))
        (catch java.lang.IllegalMonitorStateException e
          (Thread/sleep 1000)
          (if (re-find #"Current thread is not owner of the lock!"
                       (.getMessage e))
            (assoc op :type :fail, :error :not-lock-owner)
            (throw e)))
        (catch java.io.IOException e
          (Thread/sleep 1000)
          (condp re-find (.getMessage e)
            ; This indicates that the Hazelcast client doesn't have a remote
            ; peer available, and that the message was never sent.
            #"Packet is not send to owner address"
            (assoc op :type :fail, :error :client-down)

            (throw e)))))

     (teardown! [this test]
       (.shutdown conn)))))

(def map-name "jepsen.map")
(def crdt-map-name "jepsen.crdt-map")

(defn map-client
  "Options:

    :crdt? - If true, use CRDTs for merging divergent maps."
  ([opts] (map-client nil nil opts))
  ([conn m opts]
   (reify client/Client
     (setup! [_ test node]
       (let [conn (connect node)]
         (map-client conn
                     (.getMap conn (if (:crdt? opts)
                                     crdt-map-name
                                     map-name))
                     opts)))

     (invoke! [this test op]
       (case (:f op)
         ; Note that Hazelcast serialization doesn't seem to know how to
         ; replace types like HashSet, so we're storing our sets as sorted long
         ; arrays instead.
         :add (if-let [v (.get m "hi")]
                ; We have a current set.
                (let [s  (into (sorted-set) v)
                      s' (conj s (:value op))
                      v' (long-array s')]
                  (if (.replace m "hi" v v')
                    (assoc op :type :ok)
                    (assoc op :type :fail, :error :cas-failed)))

                ; We're starting fresh.
                (let [v' (long-array (sorted-set (:value op)))]
                  ; Note that replace and putIfAbsent have opposite senses for
                  ; their return values.
                  (if (.putIfAbsent m "hi" v')
                    (assoc op :type :fail, :error :cas-failed)
                    (assoc op :type :ok))))

         :read (assoc op :type :ok, :value (into (sorted-set) (.get m "hi")))))

     (teardown! [this test]
       (.shutdown conn)))))

(defn map-workload
  "A workload for map tests, with the given client options."
  [client-opts]
  {:client    (map-client client-opts)
   :generator (->> (range)
                   (map (fn [x] {:type  :invoke
                                 :f     :add
                                 :value x}))
                   gen/seq
                   (gen/stagger 1/10))
   :final-generator (->> {:type :invoke, :f :read}
                         gen/once
                         gen/each)
   :checker   (checker/set)})


(defn workloads
  "The workloads we can run. Each workload is a map like

      {:generator         a generator of client ops
       :final-generator   a generator to run after the cluster recovers
       :client            a client to execute those ops
       :checker           a checker
       :model             for the checker}

  Note that workloads are *stateful*, since they include generators; that's why
  this is a function, instead of a constant--we may need a fresh workload if we
  run more than one test."
  []
  {:crdt-map         (map-workload {:crdt? true})
   :map              (map-workload {:crdt? false})
   :lock             {:client    (lock-client)
                      :generator (->> [{:type :invoke, :f :acquire}
                                       {:type :invoke, :f :release}]
                                      cycle
                                      gen/seq
                                      gen/each)
                      :checker   (checker/linearizable)
                      :model     (model/mutex)}
   :raft-lock       {:client    (raft-lock-client)
                      :generator (->> [{:type :invoke, :f :acquire}
                                       {:type :invoke, :f :release}]
                                      cycle
                                      gen/seq
                                      gen/each)
                      :checker   (checker/linearizable)
                      :model     (model/mutex)}
   :queue            (assoc (queue-client-and-gens)
                            :checker (checker/total-queue))
   :atomic-ref-ids   {:client (atomic-ref-id-client nil nil)
                      :generator (->> {:type :invoke, :f :generate}
                                      (gen/stagger 1))
                      :checker  (checker/unique-ids)}
   :atomic-long-ids  {:client (atomic-long-id-client nil nil)
                      :generator (->> {:type :invoke, :f :generate}
                                      (gen/stagger 1))
                      :checker  (checker/unique-ids)}
   :raft-atomic-long-ids  {:client (raft-atomic-long-id-client nil nil)
                      :generator (->> {:type :invoke, :f :generate}
                                      (gen/stagger 0.5))
                      :checker  (checker/unique-ids)}
   :raft-cas-register  {:client (raft-cas-register-client nil nil)
                        :generator (->> (gen/mix [{:type :invoke, :f :read}
                                         {:type :invoke, :f :write, :value (rand-int 5)}
                                         {:type :invoke, :f :cas, :value [(rand-int 5) (rand-int 5)]}])
                                        gen/each
                                        (gen/stagger 0.5))
                        :checker  (checker/linearizable)
                        :model     (model/cas-register 0)}
   :id-gen-ids       {:client    (id-gen-id-client nil nil)
                      :generator {:type :invoke, :f :generate}
                      :checker   (checker/unique-ids)}})

(defn hazelcast-test
  "Constructs a Jepsen test map from CLI options"
  [opts]
  (let [{:keys [generator
                final-generator
                client
                checker
                model]} (get (workloads) (:workload opts))
        generator (->> generator
                       (gen/nemesis (gen/start-stop 30 15))
                       (gen/time-limit (:time-limit opts)))
        generator (if-not final-generator
                    generator
                    (gen/phases generator
                                (gen/log "Healing cluster")
                                (gen/nemesis
                                  (gen/once {:type :info, :f :stop}))
                                (gen/log "Waiting for quiescence")
                                (gen/sleep 500)
                                (gen/clients final-generator)))]
    (merge tests/noop-test
           opts
           {:name       (str "hazelcast " (name (:workload opts)))
            :os         debian/os
            :db         (db)
            :client     client
            :nemesis    (nemesis/partition-majorities-ring)
            :generator  generator
            :checker    (checker/compose
                          {:perf     (checker/perf)
                           :timeline (timeline/html)
                           :workload checker})
            :model      model})))

(def opt-spec
  "Additional command line options"
  [[nil "--workload WORKLOAD" "Test workload to run, e.g. atomic-long-ids."
    :parse-fn keyword
    :missing  (str "--workload " (cli/one-of (workloads)))
    :validate [(workloads) (cli/one-of (workloads))]]])

(defn -main
  "Command line runner."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn   hazelcast-test
                                         :opt-spec  opt-spec})
                   (cli/serve-cmd))
            args))
