(ns cn.li.ac.vfx.thunder-bolt-sample-test
  "Locks the V4 Thunder Bolt graph to main's three-bolt plus AOE-chain shape."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [cn.li.vfx.scene :as scene]))

(defn- load-thunder-bolt-vfx []
  (edn/read-string
   (slurp (io/resource "ac/vfx-v4/arc-strike-transient.edn"))))

(defn- load-thunder-bolt-skill []
  (edn/read-string
   (slurp (io/resource "ac/skills-v4/thunder-bolt.edn"))))

(deftest thunder-bolt-v4-renders-main-and-chain-arcs-test
  (let [program (scene/compile-v4-document! (load-thunder-bolt-vfx))
        ops (scene/sample!
             program
             {:capabilities
              {:start {:x 0.0 :y 64.0 :z 0.0}
               :end {:x 0.0 :y 64.0 :z 20.0}
               :pattern :strong
               :arc-life-ticks 20
               :duration-ticks 20
               :hand-origin? true
               :aoe-origin {:x 0.0 :y 64.0 :z 20.0}
               :aoe-points [{:position {:x 2.0 :y 64.0 :z 20.0}
                             :eye-height 1.8}]
               :sound-id "academy:em.arc_strong"
               :sound-volume 0.6
               :sound-pitch 1.0
               :sound-position {:x 0.0 :y 64.0 :z 0.0}
               :age 0.0
               :progress 0.0
               :seed 17}})
        arcs (filter #(= :arc (:kind %)) ops)]
    (is (= 2 (count arcs)))
    (is (= {:pattern :strong :bolt-count 3 :hand-origin? true}
           (select-keys (first arcs) [:pattern :bolt-count :hand-origin?])))
    (is (= {:pattern :aoe
            :end-points [{:position {:x 2.0 :y 64.0 :z 20.0}
                          :eye-height 1.8}]}
           (select-keys (second arcs) [:pattern :end-points])))
    (is (= 1 (count (filter #(= :audio-one-shot (:kind %)) ops))))))

(deftest thunder-bolt-v4-effect-contract-matches-main-test
  (let [skill (load-thunder-bolt-skill)
        values (tree-seq coll? identity skill)
        lightning-nodes (filter #(and (map? %)
                                      (= :world/lightning (:component %))) values)
        vfx-node (some #(when (and (map? %)
                                   (= :effect/vfx (:component %))
                                   (= :arc-strike-transient
                                      (get-in % [:inputs :effect-id])))
                          %)
                       values)]
    ;; Main renders the strike with the arc VFX only; it does not add a second
    ;; vanilla lightning effect at the impact point.
    (is (empty? lightning-nodes))
    ;; Assert the :ref, not the whole map: cn.li.node.nid stamps every node
    ;; in a V4 graph, so this value also carries an :nid
    ;; (:n/n--entry--default-2--source-player-id) that is graph plumbing, not
    ;; part of the contract being pinned here. The original `(= {:ref ...}
    ;; ...)` could never hold; it was never observed because an unmatched
    ;; delimiter three lines up meant this namespace had never compiled.
    (is (= [:local :caster-id]
           (:ref (get-in vfx-node [:inputs :payload :source-player-id]))))))
