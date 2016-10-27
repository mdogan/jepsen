(ns jepsen.hazelcast
  (:require [clojure.tools.logging :refer :all]
            [jepsen
             [checker :as checker]
             [generator :as gen]
             [nemesis :as nemesis]
             [tests :as tests]
             [util :refer [timeout]]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.os.debian :as debian]
            [knossos.model :as model]
            [jepsen.hazelcast
             [server :as server]
             [client :as client]]
            ))

(defn r [_ _] {:type :invoke, :f :read, :value nil})
(defn w [_ _] {:type :invoke, :f :write, :value (rand-int 5)})
(defn cas [_ _] {:type :invoke, :f :cas, :value [(rand-int 5) (rand-int 5)]})
(defn add [_ _] {:type :invoke, :f :add, :value (rand-int 5)})

(defn acq [_ _] {:type :invoke, :f :acquire})
(defn rel [_ _] {:type :invoke, :f :release})

(defn node-restarter
  "Kills a random node on start, restarts it on stop."
  []
  (nemesis/node-start-stopper
    rand-nth
    (fn start [test node] (server/stop node))
    (fn stop [test node] (server/start node))))

(defn linearizable-test
  ([name version opts]
   (merge tests/noop-test
          {:name    (str "hazelcast-" name)
           :os      debian/os
           :db      (server/db version)
           :checker (checker/compose
                      {:linear   checker/linearizable
                       :timeline (timeline/html)})}
          opts)))

(defn std-gen
  [ops nemesis-start nemesis-stop time-limit]
  (->> (gen/mix ops)
       (gen/stagger 1)
       (gen/nemesis
         (gen/seq (cycle [(gen/sleep nemesis-start)
                          {:type :info, :f :start}
                          (gen/sleep nemesis-stop)
                          {:type :info, :f :stop}])))
       (gen/time-limit time-limit))
  )

(defn cas-register-test
  ([name version opts] (cas-register-test name version 10 10 60 opts))
  ([name version nemesis-start nemesis-stop time-limit opts]
   (linearizable-test (str "cas-" name) version
                      (merge {:client    (client/cas-register-client nil nil)
                              :model     (model/cas-register 0)
                              :generator (std-gen [r w cas] nemesis-start nemesis-stop time-limit)
                              }
                             opts))))

(defn counter-test
  ([name version opts] (counter-test name version 10 10 60 opts))
  ([name version nemesis-start nemesis-stop time-limit opts]
   (merge tests/noop-test
          {:name      (str "hazelcast-counter-" name)
           :os        debian/os
           :db        (server/db version)
           :client    (client/counter-client nil nil)
           :model     (model/cas-register 0)
           :checker   (checker/compose
                        {:linear   checker/counter
                         :timeline (timeline/html)})
           :generator (std-gen [r add] nemesis-start nemesis-stop time-limit)
           }
          opts)))

(defn lock-test
  ([name version opts] (lock-test name version 10 30 120 opts))
  ([name version nemesis-start nemesis-stop time-limit opts]
   (linearizable-test (str "lock-" name) version
                      (merge {:client    (client/lock-client nil nil)
                              :model     (model/mutex)
                              :generator (->> (gen/seq (cycle [acq rel]))
                                              gen/each
                                              (gen/delay 10)
                                              (gen/nemesis
                                                (gen/seq
                                                  (cycle [(gen/sleep nemesis-start)
                                                          {:type :info :f :start}
                                                          (gen/sleep nemesis-stop)
                                                          {:type :info :f :stop}])))
                                              (gen/time-limit time-limit))
                              }
                             opts))))
