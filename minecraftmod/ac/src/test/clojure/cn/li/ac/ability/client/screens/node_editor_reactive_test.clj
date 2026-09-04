(ns cn.li.ac.ability.client.screens.node-editor-reactive-test
  "Unit coverage for the node editor screen's PURE logic (document open,
   render-state shaping, layout nudging) -- everything reachable without
   a live presentation-runtime mount. open!/export! (the actual
   mount-view!/disk-write side) are exercised only by using the screen
   in-game; see the namespace's own docstring for why."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.client.screens.node-editor-reactive :as node-editor]))

(def ^:private thunder-bolt-path "src/main/resources/ac/skills/thunder_bolt.edn")
(def ^:private railgun-path "src/main/resources/ac/skills/railgun.edn")

(deftest open-document-loads-a-real-single-phase-file-test
  (let [state (node-editor/open-document thunder-bolt-path)]
    (is (= [:default] (:phases state)))
    (is (= :default (:phase state)))
    (is (seq (:order (:graph state))))
    (is (= [] (:diagnostics state)))
    (is (some? (:cost-summary state)))))

(deftest open-document-loads-a-real-multi-phase-file-test
  (let [state (node-editor/open-document railgun-path)]
    (is (> (count (:phases state)) 1))
    (is (contains? (set (:phases state)) :start))))

(deftest render-state-shape-is-consistent-with-the-ui-edn-state-schema-test
  (let [state (node-editor/open-document thunder-bolt-path)
        rendered (#'node-editor/render-state state)]
    (is (string? (:title rendered)))
    (is (string? (:phase-label rendered)))
    (is (vector? (:phase-tabs rendered)))
    (is (vector? (:canvas rendered)))
    (is (vector? (:diagnostics rendered)))
    (is (number? (:diagnostic-count rendered)))
    (is (string? (:cost-label rendered)))
    (is (boolean? (:dirty? rendered)))
    (is (= "Reload" (:reload-label rendered)))
    (is (= "Save" (:save-label rendered)))))

(deftest item->hit-classifies-nid-bearing-items-as-node-hits-test
  (is (= {:target :node :nid "n3"} (#'node-editor/item->hit {:kind :quad :role :node-body :nid "n3"})))
  (is (= {:target :node :nid "n3"} (#'node-editor/item->hit {:kind :text :role :node-label :nid "n3"})))
  (is (= {:target :canvas} (#'node-editor/item->hit {:kind :quad :x 0 :y 0}))))

(deftest nudge-node-layout-accumulates-from-the-default-position-test
  (let [state (node-editor/open-document thunder-bolt-path)
        state* (atom state)
        nid (:nid (first (:order (:graph state))))]
    (#'node-editor/nudge-node-layout! state* nid 10.0 5.0)
    (#'node-editor/nudge-node-layout! state* nid 3.0 2.0)
    (let [pos (get (:layout @state*) nid)]
      (is (= 13.0 (:x pos)))
      (is (= 7.0 (:y pos))))))
