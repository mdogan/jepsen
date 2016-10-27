(ns jepsen.hazelcast_test.counter
  (:require [clojure.test :refer :all]
            [jepsen.core :as jepsen]
            [jepsen.hazelcast :as hz]
            [jepsen.hazelcast_test]
            [jepsen.nemesis :as nemesis]))

(def version jepsen.hazelcast_test/version)

(deftest partition-halves-test
  (is (:valid? (:results (jepsen/run! (hz/counter-test "partition-random-halves" version
                                                            {:nemesis (nemesis/partition-random-halves)}))))))

(deftest pause-resume-test
  (is (:valid? (:results (jepsen/run! (hz/counter-test "pause-resume-node" version
                                                            {:nemesis (nemesis/hammer-time "java")}))))))

(deftest restart-test
  (is (:valid? (:results (jepsen/run! (hz/counter-test "node-restart" version 10 0 120
                                                            {:nemesis (hz/node-restarter)}))))))

(deftest clock-scrambler-test
  (is (:valid? (:results (jepsen/run! (hz/counter-test "clock-scrambler" version 30 30 60
                                                            {:nemesis (nemesis/clock-scrambler 300)}))))))



