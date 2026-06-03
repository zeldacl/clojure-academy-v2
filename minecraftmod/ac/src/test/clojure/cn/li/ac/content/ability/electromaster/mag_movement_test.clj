(ns cn.li.ac.content.ability.electromaster.mag-movement-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
            [cn.li.ac.ability.effects.fx :as fx-op]
            [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.ac.ability.effects.motion :as motion-op]
            [cn.li.ac.ability.effects.state :as state-op]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.content.ability.electromaster.mag-movement :as mag-movement]
            [cn.li.mcmod.platform.raycast :as raycast]))

(defn- skill-actions []
  (:actions (var-get (ns-resolve 'cn.li.ac.content.ability.electromaster.mag-movement
                                 'mag-movement-skill))))

(defn- skill-def []
  (var-get (ns-resolve 'cn.li.ac.content.ability.electromaster.mag-movement
                       'mag-movement-skill)))

(defn- mk-context-store [ctx-id seed]
  (atom {ctx-id seed}))

(deftest pattern-is-hold-channel-and-cost-fail-present-test
  (let [spec (skill-def)]
    (is (= :hold-channel (:pattern spec)))
    (is (fn? (get-in spec [:actions :cost-fail!])))))

(deftest cost-fail-down-finalizes-without-exp-test
  (let [ctx-id "ctx-down-fail"
        contexts* (mk-context-store ctx-id {:skill-state {:has-target false :finalized? false}})
        fx* (atom [])
        exp* (atom [])
        terminated* (atom [])
        cost-fail! (get (skill-actions) :cost-fail!)]
    (with-redefs [ctx/get-context (fn [id] (get @contexts* id))
                  ctx-skill/update-skill-state-root! (fn [id f & args]
                                        (swap! contexts* update id #(apply f % args))
                                        nil)
                  ctx/terminate-context! (fn [id _]
                                           (swap! terminated* conj id)
                                           nil)
                  fx-op/execute-fx! (fn [_evt params]
                                      (swap! fx* conj params)
                                      nil)
                  motion-op/execute-reset-fall-damage! (fn [_evt _params] nil)
                  skill-effects/add-skill-exp! (fn [player-id skill-id amount]
                                                 (swap! exp* conj [player-id skill-id amount])
                                                 nil)]
      (cost-fail! {:ctx-id ctx-id :player-id "p1" :cost-stage :down}))
    (is (empty? @exp*))
    (is (= [{:topic :mag-movement/fx-end :payload {:mode :end}}] @fx*))
    (is (= [ctx-id] @terminated*))
    (is (nil? (get-in @contexts* [ctx-id :skill-state])))))

(deftest down-no-target-terminates-without-exp-test
  (let [ctx-id "ctx-no-target"
        contexts* (mk-context-store ctx-id {})
        fx* (atom [])
        exp* (atom [])
        terminated* (atom [])
        down! (get (skill-actions) :down!)]
    (with-redefs [mag-movement/resolve-target (fn [_] nil)
                  mag-movement/player-pos (fn [_] {:x 0.0 :y 0.0 :z 0.0})
                  ctx/get-context (fn [id] (get @contexts* id))
                  ctx-skill/update-skill-state-root! (fn [id f & args]
                                        (swap! contexts* update id #(apply f % args))
                                        nil)
                  ctx/terminate-context! (fn [id _]
                                           (swap! terminated* conj id)
                                           nil)
                  fx-op/execute-fx! (fn [_evt params]
                                      (swap! fx* conj params)
                                      nil)
                  motion-op/execute-reset-fall-damage! (fn [_evt _params] nil)
                  skill-effects/add-skill-exp! (fn [& args]
                                                 (swap! exp* conj args)
                                                 nil)]
      (down! {:ctx-id ctx-id :player-id "p1" :exp 0.4}))
    (is (empty? @exp*))
    (is (= [{:topic :mag-movement/fx-end :payload {:mode :end}}] @fx*))
    (is (= [ctx-id] @terminated*))
    (is (nil? (get-in @contexts* [ctx-id :skill-state])))))

(deftest tick-cost-fail-finalizes-once-with-exp-test
  (let [ctx-id "ctx-tick-fail"
        contexts* (mk-context-store ctx-id {:skill-state {:has-target true
                                                          :finalized? false
                                                          :target-kind :block
                                                          :target-x 1.0
                                                          :target-y 0.0
                                                          :target-z 0.0
                                                          :motion-x 0.0
                                                          :motion-y 0.0
                                                          :motion-z 0.0
                                                          :movement-ticks 0
                                                          :start-x 0.0
                                                          :start-y 0.0
                                                          :start-z 0.0
                                                          :overload-floor 10.0}})
        fx* (atom [])
        exp* (atom [])
        terminated* (atom [])
        tick! (get (skill-actions) :tick!)]
    (with-redefs [mag-movement/player-pos (fn [_] {:x 3.0 :y 4.0 :z 0.0})
                  mag-movement/cfg-double (fn [k]
                                            (case k
                                              :progression.exp-min 0.005
                                              :progression.exp-distance-scale 0.0011
                                              :movement.acceleration 0.08
                                              :targeting.target-update-radius 4.0
                                              0.0))
                  ctx/get-context (fn [id] (get @contexts* id))
                  ctx-skill/update-skill-state-root! (fn [id f & args]
                                        (swap! contexts* update id #(apply f % args))
                                        nil)
                  ctx/terminate-context! (fn [id _]
                                           (swap! terminated* conj id)
                                           nil)
                  fx-op/execute-fx! (fn [_evt params]
                                      (when (= :mag-movement/fx-end (:topic params))
                                        (swap! fx* conj params))
                                      nil)
                  motion-op/execute-reset-fall-damage! (fn [_evt _params] nil)
                  state-op/execute-overload-floor! (fn [_evt _params] nil)
                  skill-effects/add-skill-exp! (fn [player-id skill-id amount]
                                                 (swap! exp* conj [player-id skill-id amount])
                                                 nil)]
      (tick! {:ctx-id ctx-id :player-id "p1" :cost-ok? false})
      ;; Repeat to verify idempotence.
      (tick! {:ctx-id ctx-id :player-id "p1" :cost-ok? false}))
    (is (= 1 (count @exp*)))
    (is (= 1 (count @fx*)))
    (is (= [ctx-id] @terminated*))
    (is (nil? (get-in @contexts* [ctx-id :skill-state])))))

(deftest tick-target-lost-finalizes-with-exp-test
  (let [ctx-id "ctx-target-lost"
        contexts* (mk-context-store ctx-id {:skill-state {:has-target true
                                                          :finalized? false
                                                          :target-kind :entity
                                                          :target-world-id "w"
                                                          :target-entity-uuid "e1"
                                                          :target-x 2.0
                                                          :target-y 0.0
                                                          :target-z 0.0
                                                          :motion-x 0.0
                                                          :motion-y 0.0
                                                          :motion-z 0.0
                                                          :movement-ticks 2
                                                          :start-x 0.0
                                                          :start-y 0.0
                                                          :start-z 0.0
                                                          :overload-floor 10.0}})
        fx* (atom [])
        exp* (atom [])
        terminated* (atom [])
        tick! (get (skill-actions) :tick!)]
    (with-redefs [mag-movement/update-entity-target (fn [_] nil)
                  mag-movement/player-pos (fn [_] {:x 0.0 :y 0.0 :z 5.0})
                  mag-movement/cfg-double (fn [k]
                                            (case k
                                              :progression.exp-min 0.005
                                              :progression.exp-distance-scale 0.0011
                                              :targeting.target-update-radius 4.0
                                              0.0))
                  ctx/get-context (fn [id] (get @contexts* id))
                  ctx-skill/update-skill-state-root! (fn [id f & args]
                                        (swap! contexts* update id #(apply f % args))
                                        nil)
                  ctx/terminate-context! (fn [id _]
                                           (swap! terminated* conj id)
                                           nil)
                  fx-op/execute-fx! (fn [_evt params]
                                      (swap! fx* conj params)
                                      nil)
                  motion-op/execute-reset-fall-damage! (fn [_evt _params] nil)
                  state-op/execute-overload-floor! (fn [_evt _params] nil)
                  skill-effects/add-skill-exp! (fn [player-id skill-id amount]
                                                 (swap! exp* conj [player-id skill-id amount])
                                                 nil)]
      (tick! {:ctx-id ctx-id :player-id "p1" :cost-ok? true}))
    (is (= 1 (count @exp*)))
    (is (= [{:topic :mag-movement/fx-end :payload {:mode :end}}] @fx*))
    (is (= [ctx-id] @terminated*))
    (is (nil? (get-in @contexts* [ctx-id :skill-state])))))

(deftest resolve-target-uses-hit-vec-and-eye-height-test
  (with-redefs [mag-movement/is-metal-block? (fn [_] true)
                mag-movement/is-metal-entity? (fn [_] true)
                raycast/get-player-look-vector* (fn [_ _] {:x 0.0 :y 0.0 :z 1.0})
                geom/eye-pos (fn [_] {:x 10.0 :y 20.0 :z 30.0})
                geom/world-id-of (fn [_] "w")
                mag-movement/cfg-double (fn [k]
                                          (case k
                                            :targeting.range 25.0
                                            0.0))
                raycast/raycast-combined* (fn [_ _ _ _ _ _ _ _ _]
                                           {:hit-type :block
                                            :block-id "minecraft:iron_block"
                                            :x 1.25 :y 2.5 :z 3.75})]
    (is (= {:target-kind :block
            :target-world-id "w"
            :target-x 1.25
            :target-y 2.5
            :target-z 3.75
            :target-block-id "minecraft:iron_block"}
           (@#'cn.li.ac.content.ability.electromaster.mag-movement/resolve-target "p1"))))
  (with-redefs [mag-movement/is-metal-block? (fn [_] true)
                mag-movement/is-metal-entity? (fn [_] true)
                raycast/get-player-look-vector* (fn [_ _] {:x 0.0 :y 0.0 :z 1.0})
                geom/eye-pos (fn [_] {:x 10.0 :y 20.0 :z 30.0})
                geom/world-id-of (fn [_] "w")
                mag-movement/cfg-double (fn [k]
                                          (case k
                                            :targeting.range 25.0
                                            0.0))
                raycast/raycast-combined* (fn [_ _ _ _ _ _ _ _ _]
                                           {:hit-type :entity
                                            :type "minecraft:iron_golem"
                                            :uuid "e-1"
                                            :x 4.0 :y 5.0 :z 6.0
                                            :eye-height 1.9})]
    (is (= {:target-kind :entity
            :target-world-id "w"
            :target-entity-uuid "e-1"
            :target-entity-type "minecraft:iron_golem"
            :target-x 4.0
            :target-y 6.9
            :target-z 6.0}
           (@#'cn.li.ac.content.ability.electromaster.mag-movement/resolve-target "p1")))))

(deftest up-then-abort-does-not-double-finalize-test
  (let [ctx-id "ctx-up-abort"
        contexts* (mk-context-store ctx-id {:skill-state {:has-target true
                                                          :finalized? false
                                                          :target-kind :block
                                                          :target-x 1.0
                                                          :target-y 0.0
                                                          :target-z 0.0
                                                          :movement-ticks 5
                                                          :start-x 0.0
                                                          :start-y 0.0
                                                          :start-z 0.0}})
        fx* (atom [])
        exp* (atom [])
        terminated* (atom [])
        up! (get (skill-actions) :up!)
        abort! (get (skill-actions) :abort!)]
    (with-redefs [mag-movement/player-pos (fn [_] {:x 1.0 :y 0.0 :z 0.0})
                  mag-movement/cfg-double (fn [k]
                                            (case k
                                              :progression.exp-min 0.005
                                              :progression.exp-distance-scale 0.0011
                                              0.0))
                  ctx/get-context (fn [id] (get @contexts* id))
                  ctx-skill/update-skill-state-root! (fn [id f & args]
                                        (swap! contexts* update id #(apply f % args))
                                        nil)
                  ctx/terminate-context! (fn [id _]
                                           (swap! terminated* conj id)
                                           nil)
                  fx-op/execute-fx! (fn [_evt params]
                                      (swap! fx* conj params)
                                      nil)
                  motion-op/execute-reset-fall-damage! (fn [_evt _params] nil)
                  skill-effects/add-skill-exp! (fn [player-id skill-id amount]
                                                 (swap! exp* conj [player-id skill-id amount])
                                                 nil)]
      (up! {:ctx-id ctx-id :player-id "p1"})
      (abort! {:ctx-id ctx-id :player-id "p1"}))
    (is (= 1 (count @exp*)))
    (is (= 1 (count @fx*)))
    (is (= [ctx-id] @terminated*))))

