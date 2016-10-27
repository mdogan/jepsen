(ns jepsen.hazelcast.client
  (:require [clojure.tools.logging :refer :all]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jepsen
             [client :as client]
             [util :refer [timeout]]]
            )
  (:import (java.io ByteArrayInputStream)
           (com.hazelcast.client HazelcastClient)
           (com.hazelcast.client.config XmlClientConfigBuilder)))


(defn prepare-client-config
  "client config"
  [node]
  (info "Client will connect to " (name node))
  (ByteArrayInputStream. (.getBytes (str/replace (slurp (io/resource "hazelcast-client.xml"))
                                                 #"<!-- MEMBER -->" (str "<address>" (name node) ":5701</address>")))))


(defn start-client
  "new client"
  [node]
  (HazelcastClient/newHazelcastClient (.build (XmlClientConfigBuilder. (prepare-client-config node)))))

(defn cas-register-client
  "A client for a single compare-and-set register"
  [hz cas-register]
  (reify client/Client
    (setup! [_ test node]
      (let [hz (start-client node) atomicLong (.getAtomicLong hz "value")]
        (cas-register-client hz atomicLong)))

    (invoke! [_ test op]
      (case (:f op)
        :read (assoc op :type :ok, :value (.get cas-register))
        :write (do (.set cas-register (:value op))
                   (assoc op :type :ok))
        :cas (let [[currentV newV] (:value op)]
               (if (.compareAndSet cas-register currentV newV)
                 (assoc op :type :ok)
                 (assoc op :type :fail)
                 ))
        )
      )

    (teardown! [_ test]
      (.shutdown hz))))

(defn counter-client
  "A client for a single atomic long"
  [hz counter]
  (reify client/Client
    (setup! [_ test node]
      (let [hz (start-client node) atomicLong (.getAtomicLong hz "value")]
        (counter-client hz atomicLong)))

    (invoke! [_ test op]
      (case (:f op)
        :read (assoc op :type :ok, :value (.get counter))
        :add (do (.addAndGet counter (:value op))
                 (assoc op :type :ok))
        )
      )

    (teardown! [_ test]
      (.shutdown hz))))


(defn lock-client
  "A client for a single lock"
  [client lock]
  (reify client/Client
    (setup! [_ test node]
      (let [hz (start-client node) lock (.getLock hz "mutex")]
        (lock-client hz lock)))

    (invoke! [_ test op]
      (case (:f op)
        :acquire (if (.tryLock lock)
                   (assoc op :type :ok)
                   (assoc op :type :fail))

        :release (try (.unlock lock)
                      (assoc op :type :ok)
                      (catch IllegalMonitorStateException e
                        (error (.getMessage e))
                        (assoc op :type :fail)
                        ))
        ))

    (teardown! [_ test]
      (.shutdown client))))
