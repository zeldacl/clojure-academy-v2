(ns cn.li.ac.ability.server.effect.motion-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.effects.motion :as motion]
            [cn.li.mcmod.platform.player-motion :as player-motion]
            [cn.li.mcmod.platform.entity-motion :as entity-motion]))

(defn- recording-player-motion [calls]
  (reify player-motion/IPlayerMotion
    (set-velocity! [_ pid x y z]
      (swap! calls conj [:vel pid x y z])
      true)
    (add-velocity! [_ _ _ _ _] false)
    (get-velocity [_ _] nil)
    (set-on-ground! [_ _ _] false)
    (is-on-ground? [_ _] false)
    (dismount-riding! [_ _] false)))

(defn- recording-entity-motion [calls]
  (reify entity-motion/IEntityMotion
    (set-velocity! [_ _ _ _ _ _] false)
    (add-velocity! [_ wid uuid x y z]
      (swap! calls conj [:add wid uuid x y z])
      true)
    (discard-entity! [_ _ _] false)
    (get-velocity [_ _ _] nil)))

(deftest set-player-velocity-records-when-bound-test
  (let [calls (atom [])
        pm (recording-player-motion calls)
        evt {:player-id "p1"}]
    (binding [player-motion/*player-motion* pm]
      (is (= evt (motion/execute-set-player-velocity! evt {:x 1.0 :y 2.0 :z -0.5}))))
    (is (= [[:vel "p1" 1.0 2.0 -0.5]] @calls))))

(deftest set-player-velocity-noop-when-unbound-test
  (let [evt {:player-id "p2"}]
    (binding [player-motion/*player-motion* nil]
      (is (= evt (motion/execute-set-player-velocity! evt {:x 1.0 :y 0.0 :z 0.0}))))))

(deftest add-entity-velocity-resolves-target-test
  (let [calls (atom [])
        em (recording-entity-motion calls)
        evt {:world-id "dim1" :tgt "ent-uuid"}]
    (binding [entity-motion/*entity-motion* em]
      (is (= evt (motion/execute-add-entity-velocity! evt {:target :tgt :x 0.1 :y 0.2 :z 0.3}))))
    (is (= [[:add "dim1" "ent-uuid" 0.1 0.2 0.3]] @calls))))

