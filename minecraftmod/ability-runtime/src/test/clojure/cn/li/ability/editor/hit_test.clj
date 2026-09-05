(ns cn.li.ability.editor.hit-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ability.editor.hit :as hit]))

(deftest press-on-node-body-starts-a-move-drag-test
  (let [state (hit/on-down hit/idle {:target :node :nid "n1"} 10 10)]
    (is (= :dragging-node (:mode state)))
    (is (hit/dragging? state))))

(deftest press-on-pin-starts-a-wire-drag-test
  (let [state (hit/on-down hit/idle {:target :pin :nid "n1" :pin :out :key :result} 10 10)]
    (is (= :dragging-wire (:mode state)))
    (is (= "n1" (:from-nid state)))))

(deftest press-on-canvas-starts-a-pan-test
  (let [state (hit/on-down hit/idle {:target :canvas} 10 10)]
    (is (= :panning (:mode state)))))

(deftest move-node-drag-then-release-emits-move-node-action-with-delta-test
  (let [pressed (hit/on-down hit/idle {:target :node :nid "n1"} 100 100)
        moved (hit/on-move pressed 140 130)
        {:keys [state action]} (hit/on-up moved {:target :node :nid "n1"} 140 130)]
    (is (= hit/idle state))
    (is (= :move-node (:kind action)))
    (is (= "n1" (:nid action)))
    (is (= 40 (:dx action)))
    (is (= 30 (:dy action)))))

(deftest wire-drag-released-on-a-different-pin-connects-test
  (let [pressed (hit/on-down hit/idle {:target :pin :nid "n1" :pin :out :key :result} 0 0)
        {:keys [state action]} (hit/on-up pressed {:target :pin :nid "n2" :pin :in :key :target} 50 50)]
    (is (= hit/idle state))
    (is (= :connect-wire (:kind action)))
    (is (= "n1" (:from-nid action)))
    (is (= "n2" (:to-nid action)))))

(deftest wire-drag-released-on-canvas-cancels-test
  (let [pressed (hit/on-down hit/idle {:target :pin :nid "n1" :pin :out :key :result} 0 0)
        {:keys [state action]} (hit/on-up pressed {:target :canvas} 50 50)]
    (is (= hit/idle state))
    (is (= :cancel-wire (:kind action)))))

(deftest wire-drag-released-on-the-same-pin-cancels-test
  ;; Releasing back on the SAME pin it started from is not a self-connection.
  (let [pressed (hit/on-down hit/idle {:target :pin :nid "n1" :pin :out :key :result} 0 0)
        {:keys [action]} (hit/on-up pressed {:target :pin :nid "n1" :pin :out :key :result} 0 0)]
    (is (= :cancel-wire (:kind action)))))

(deftest pan-drag-then-release-emits-pan-viewport-action-test
  (let [pressed (hit/on-down hit/idle {:target :canvas} 0 0)
        moved (hit/on-move pressed -20 -10)
        {:keys [action]} (hit/on-up moved {:target :canvas} -20 -10)]
    (is (= :pan-viewport (:kind action)))
    (is (= -20 (:dx action)))
    (is (= -10 (:dy action)))))

(deftest move-on-idle-is-a-no-op-test
  (is (= hit/idle (hit/on-move hit/idle 5 5))))
