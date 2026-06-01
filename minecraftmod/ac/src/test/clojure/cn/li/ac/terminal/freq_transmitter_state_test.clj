(ns cn.li.ac.terminal.freq-transmitter-state-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.terminal.apps.freq-transmitter-state :as state]))

(deftest matrix-auth-flow-test
  (let [s0 (state/initial-state {:now-ms 0})
        s1 (state/choose-matrix s0 {:x 1 :y 2 :z 3} 10)
        s2 (state/auth-ok s1 20)
        s3 (state/link-ok s2 30)]
    (is (= :auth-matrix (:phase s1)))
    (is (= :link-node (:phase s2)))
    (is (= :intro (:phase s3)))
    (is (= :linked (:status s3)))))

(deftest node-auth-timeout-test
  (let [s0 (state/initial-state {:now-ms 0})
        s1 (state/choose-node s0 {:x 1 :y 2 :z 3} 1000)
        s2 (state/tick-timeout s1 (+ 1000 state/default-auth-timeout-ms 1))]
    (is (= :auth-node (:phase s1)))
    (is (= :intro (:phase s2)))
    (is (= :timeout (:status s2)))))

