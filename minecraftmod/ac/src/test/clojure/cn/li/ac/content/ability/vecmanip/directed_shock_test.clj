(ns cn.li.ac.content.ability.vecmanip.directed-shock-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.service.dispatcher :as ctx]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.ac.content.ability.fx-helpers :as fx]
            [cn.li.ac.content.ability.vecmanip.directed-shock :as ds]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.entity-motion :as entity-motion]))

(def ^:private spec ds/directed-shock)

(defn- mock-cfg-int [field]
  (case field
    :charge.min-ticks 6
    :charge.max-accepted-ticks 50
    :charge.max-tolerant-ticks 200
    :charge.punch-anim-ticks 6
    0))

(defn- mock-cfg-double [field]
  (case field
    :targeting.raycast-distance 3.0
    :targeting.eye-height 1.62
    :movement.hit-impulse 0.24
    :movement.knockback-y-adjust 0.6
    :movement.knockback-scale -0.7
    :movement.knockback-exp-threshold 0.25
    :progression.exp-hit 0.0035
    :progression.exp-miss 0.001
    0.0))

(defn- mock-cfg-lerp [field _exp]
  (case field
    :combat.damage 12.0
    :cost.up.cp 75.0
    :cost.up.overload 14.0
    0.0))

(defn- mock-cfg-lerp-int [field _exp]
  (case field
    :cooldown.ticks 40
    0))

(defn- make-context-mocks [initial-ctx]
  (let [ctx* (atom initial-ctx)
        terminate-calls* (atom [])]
    {:ctx* ctx*
     :terminate-calls* terminate-calls*
     :get-context (fn [_] @ctx*)
     :update-context! (fn [_ f & args]
                        (swap! ctx* #(when % (apply f % args))))
     :terminate-context! (fn [ctx-id _]
                           (swap! terminate-calls* conj ctx-id))}))

(deftest charge-window-open-interval-test
  (let [up-fn (get-in spec [:actions :up!])]
    (doseq [ticks [6 50]]
      (let [{:keys [get-context update-context! terminate-context! terminate-calls*]}
            (make-context-mocks {:skill-state {:charge-ticks ticks :performed? true}})
            end-calls* (atom [])
            trace-calls* (atom 0)]
        (with-redefs [ds/cfg-int mock-cfg-int
                      ds/cfg-double mock-cfg-double
                      ds/cfg-lerp mock-cfg-lerp
                      ds/cfg-lerp-int mock-cfg-lerp-int
                      ctx/get-context get-context
                      ctx/update-context! update-context!
                      ctx/terminate-context! terminate-context!
                      fx/send-end! (fn [ctx-id ch payload]
                                     (swap! end-calls* conj [ctx-id ch payload]))
                      ds/entity-trace (fn [_]
                                        (swap! trace-calls* inc)
                                        nil)]
              (up-fn {:player-id "p1" :ctx-id "ctx-1" :exp 0.5 :cost-ok? true}))
        (is (= [["ctx-1" :directed-shock/fx-end {:performed? false}]] @end-calls*))
        (is (= ["ctx-1"] @terminate-calls*))
        (is (= 0 @trace-calls*))))))

(deftest hit-path-applies-effects-and-sets-cooldown-test
  (let [up-fn (get-in spec [:actions :up!])
        {:keys [ctx* get-context update-context! terminate-context! terminate-calls*]}
        (make-context-mocks {:skill-state {:charge-ticks 10 :performed? false :punched? false :punch-ticks 0}})
        damage-calls* (atom [])
        add-velocity-calls* (atom [])
        set-velocity-calls* (atom [])
        perform-calls* (atom [])
        end-calls* (atom [])
        cooldown-calls* (atom [])
        exp-calls* (atom [])]
    (with-redefs [ds/cfg-int mock-cfg-int
                  ds/cfg-double mock-cfg-double
                  ds/cfg-lerp mock-cfg-lerp
                  ds/cfg-lerp-int mock-cfg-lerp-int
                  ctx/get-context get-context
                  ctx/update-context! update-context!
                  ctx/terminate-context! terminate-context!
                  geom/world-id-of (fn [_] "w")
                  geom/eye-pos (fn [_] {:x 0.0 :y 1.62 :z 0.0})
                  ds/entity-trace (fn [_]
                                    {:entity-id "e1" :x 1.0 :y 2.0 :z 3.0 :eye-height 1.8})
                  entity-damage/apply-direct-damage! (fn [_ world-id target-id damage kind]
                                                      (swap! damage-calls* conj [world-id target-id damage kind]))
                  entity-motion/add-velocity! (fn [_ world-id target-id x y z]
                                                (swap! add-velocity-calls* conj [world-id target-id x y z]))
                  entity-motion/set-velocity! (fn [_ world-id target-id x y z]
                                                (swap! set-velocity-calls* conj [world-id target-id x y z]))
                  fx/send-perform! (fn [ctx-id ch payload]
                                     (swap! perform-calls* conj [ctx-id ch payload]))
                  fx/send-end! (fn [ctx-id ch payload]
                                 (swap! end-calls* conj [ctx-id ch payload]))
                  skill-effects/set-main-cooldown! (fn [player-id skill-id ticks]
                                                     (swap! cooldown-calls* conj [player-id skill-id ticks]))
                  skill-effects/add-skill-exp! (fn [player-id skill-id amount]
                                                 (swap! exp-calls* conj [player-id skill-id amount]))]
              (binding [entity-damage/*entity-damage* :mock-dmg
                entity-motion/*entity-motion* :mock-motion]
        (up-fn {:player-id "p1" :ctx-id "ctx-hit" :exp 0.3 :cost-ok? true})))

    (is (= [["w" "e1" 12.0 :generic]] @damage-calls*))
    (is (= 1 (count @set-velocity-calls*)))
    (is (= 1 (count @add-velocity-calls*)))
    (is (= 1 (count @perform-calls*)))
    (is (empty? @end-calls*))
    (is (empty? @terminate-calls*))
    (is (= [["p1" :directed-shock 40]] @cooldown-calls*))
    (is (= [["p1" :directed-shock 0.0035]] @exp-calls*))
    (is (= true (get-in @ctx* [:skill-state :performed?])))
    (is (= true (get-in @ctx* [:skill-state :punched?])))))

(deftest miss-path-adds-miss-exp-without-cooldown-test
  (let [up-fn (get-in spec [:actions :up!])
        {:keys [ctx* get-context update-context! terminate-context! terminate-calls*]}
        (make-context-mocks {:skill-state {:charge-ticks 10 :performed? true}})
        end-calls* (atom [])
        cooldown-calls* (atom [])
        exp-calls* (atom [])]
    (with-redefs [ds/cfg-int mock-cfg-int
                  ds/cfg-double mock-cfg-double
                  ds/cfg-lerp mock-cfg-lerp
                  ds/cfg-lerp-int mock-cfg-lerp-int
                  ctx/get-context get-context
                  ctx/update-context! update-context!
                  ctx/terminate-context! terminate-context!
                  geom/world-id-of (fn [_] "w")
                  geom/eye-pos (fn [_] {:x 0.0 :y 1.62 :z 0.0})
                  ds/entity-trace (fn [_] nil)
                  fx/send-end! (fn [ctx-id ch payload]
                                 (swap! end-calls* conj [ctx-id ch payload]))
                  skill-effects/set-main-cooldown! (fn [player-id skill-id ticks]
                                                     (swap! cooldown-calls* conj [player-id skill-id ticks]))
                  skill-effects/add-skill-exp! (fn [player-id skill-id amount]
                                                 (swap! exp-calls* conj [player-id skill-id amount]))]
      (up-fn {:player-id "p1" :ctx-id "ctx-miss" :exp 0.3 :cost-ok? true}))

    (is (= [["ctx-miss" :directed-shock/fx-end {:performed? false}]] @end-calls*))
    (is (= ["ctx-miss"] @terminate-calls*))
    (is (empty? @cooldown-calls*))
    (is (= [["p1" :directed-shock 0.001]] @exp-calls*))
    (is (nil? (:skill-state @ctx*)))))

(deftest punch-tick-terminates-successful-context-test
  (let [tick-fn (get-in spec [:actions :tick!])
        {:keys [ctx* get-context update-context! terminate-context! terminate-calls*]}
        (make-context-mocks {:skill-state {:charge-ticks 12 :performed? true :punched? true :punch-ticks 6}})
        end-calls* (atom [])]
    (with-redefs [ds/cfg-int mock-cfg-int
                  ctx/get-context get-context
                  ctx/update-context! update-context!
                  ctx/terminate-context! terminate-context!
                  fx/send-end! (fn [ctx-id ch payload]
                                 (swap! end-calls* conj [ctx-id ch payload]))]
      (tick-fn {:ctx-id "ctx-punch"}))

    (is (= [["ctx-punch" :directed-shock/fx-end {:performed? true}]] @end-calls*))
    (is (= ["ctx-punch"] @terminate-calls*))
    (is (nil? (:skill-state @ctx*)))))

(deftest abort-cleans-up-and-terminates-test
  (let [abort-fn (get-in spec [:actions :abort!])
        {:keys [ctx* get-context update-context! terminate-context! terminate-calls*]}
        (make-context-mocks {:skill-state {:charge-ticks 3 :performed? false}})
        end-calls* (atom [])]
    (with-redefs [ctx/get-context get-context
                  ctx/update-context! update-context!
                  ctx/terminate-context! terminate-context!
                  fx/send-end! (fn [ctx-id ch payload]
                                 (swap! end-calls* conj [ctx-id ch payload]))]
      (abort-fn {:ctx-id "ctx-abort"}))

    (is (= [["ctx-abort" :directed-shock/fx-end {:performed? false}]] @end-calls*))
    (is (= ["ctx-abort"] @terminate-calls*))
    (is (nil? (:skill-state @ctx*)))))
