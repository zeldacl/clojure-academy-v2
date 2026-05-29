(ns cn.li.ac.content.ability.electromaster.current-charging-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.service.dispatcher :as ctx]
            [cn.li.ac.content.ability.electromaster.current-charging :as current-charging]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.runtime-interop :as interop]))

(defn- skill-actions []
  (:actions (var-get (ns-resolve 'cn.li.ac.content.ability.electromaster.current-charging
                                 'current_charging_skill))))

(defn- skill-def []
  (var-get (ns-resolve 'cn.li.ac.content.ability.electromaster.current-charging
                       'current_charging_skill)))

(defn- mk-context-store [ctx-id seed]
  (atom {ctx-id seed}))

(deftest pattern-is-hold-channel-test
  (is (= :hold-channel (:pattern (skill-def)))))

(deftest down-action-initializes-state-and-starts-fx-test
  (let [ctx-id "ctx-1"
        contexts* (mk-context-store ctx-id {})
        fx* (atom [])
        down! (get (skill-actions) :down!)]
    (with-redefs [current-charging/main-hand-item (fn [_] :stack)
                  current-charging/cfg-lerp (fn [_ _] 52.0)
                  ctx/get-context (fn [id] (get @contexts* id))
                  ctx/update-context! (fn [id f & args]
                                        (swap! contexts* update id #(apply f % args))
                                        nil)
                  ctx/ctx-send-to-client! (fn [id ch payload]
                                            (swap! fx* conj [id ch payload])
                                            nil)]
      (down! {:player-id "p1" :ctx-id ctx-id :exp 0.4}))
    (is (= {:mode :item
            :is-item true
            :good? false
            :exp 0.4
            :charge-ticks 0
            :overload-floor 52.0
            :target nil
            :block-pos nil
            :charged 0.0}
           (get-in @contexts* [ctx-id :skill-state])))
    (is (= [[ctx-id :current-charging/fx-start {:is-item true
                          :source-player-id "p1"}]] @fx*))))

(deftest tick-item-path-updates-state-exp-and-fx-test
  (let [ctx-id "ctx-item"
        contexts* (mk-context-store ctx-id {:skill-state {:mode :item
                                                          :is-item true
                                                          :exp 0.5
                                                          :charge-ticks 0
                                                          :overload-floor 40.0}})
        fx* (atom [])
        exp* (atom [])
        charge* (atom [])
        floor* (atom [])
        tick! (get (skill-actions) :tick!)]
    (with-redefs [current-charging/main-hand-item (fn [_] :stack)
                  current-charging/cfg-lerp (fn [_ _] 30.0)
                  current-charging/cfg-double (fn [k]
                                                (case k
                                                  :progression.exp-effective 0.0001
                                                  :progression.exp-ineffective 0.00003
                                                  0.0))
                  energy/is-energy-item-supported? (fn [_] true)
                  energy/charge-energy-to-item (fn [stack amt sim?]
                                                 (swap! charge* conj [stack amt sim?])
                                                 nil)
                  skill-effects/add-skill-exp! (fn [pid sid amount]
                                                 (swap! exp* conj [pid sid amount])
                                                 nil)
                  skill-effects/enforce-overload-floor! (fn [pid floor]
                                                          (swap! floor* conj [pid floor])
                                                          nil)
                  ctx/get-context (fn [id] (get @contexts* id))
                  ctx/update-context! (fn [id f & args]
                                        (swap! contexts* update id #(apply f % args))
                                        nil)
                  ctx/ctx-send-to-client! (fn [id ch payload]
                                            (swap! fx* conj [id ch payload])
                                            nil)]
      (tick! {:player-id "p1" :ctx-id ctx-id}))
    (is (= 1 (get-in @contexts* [ctx-id :skill-state :charge-ticks])))
    (is (true? (get-in @contexts* [ctx-id :skill-state :good?])))
    (is (= [["p1" :current-charging 1.0E-4]] @exp*))
    (is (= [[:stack 30.0 false]] @charge*))
    (is (= [["p1" 40.0]] @floor*))
    (is (= [[ctx-id :current-charging/fx-update
             {:is-item true :good? true :charge-ticks 1 :exp 0.5
              :source-player-id "p1"}]]
           @fx*))))

(deftest tick-block-path-spawns-arc-on-sixth-tick-test
  (let [ctx-id "ctx-block"
        contexts* (mk-context-store ctx-id {:skill-state {:mode :block
                                                          :is-item false
                                                          :exp 0.5
                                                          :charge-ticks 5
                                                          :overload-floor 50.0}})
        fx* (atom [])
        spawned* (atom [])
        tick! (get (skill-actions) :tick!)]
    (with-redefs [current-charging/cfg-lerp (fn [_ _] 20.0)
                  current-charging/cfg-double (fn [_] 0.0001)
                  current-charging/charge-block-target! (fn [_ _]
                                                          {:effective? true
                                                           :charged 8.0
                                                           :block-pos [1 2 3]
                                                           :ray-end {:x 1.0 :y 2.0 :z 3.0}})
                  skill-effects/add-skill-exp! (fn [& _] nil)
                  skill-effects/enforce-overload-floor! (fn [& _] nil)
                  interop/*runtime-interop* :mock
                  interop/get-player-view (fn [_ _]
                                            {:world-id "minecraft:overworld"
                                             :x 0.0 :y 64.0 :z 0.0
                                             :look-x 1.0 :look-y 0.0 :look-z 0.0})
                  entity/player-spawn-entity-by-id! (fn [player eid yaw]
                                                      (swap! spawned* conj [player eid yaw])
                                                      true)
                  ctx/get-context (fn [id] (get @contexts* id))
                  ctx/update-context! (fn [id f & args]
                                        (swap! contexts* update id #(apply f % args))
                                        nil)
                  ctx/ctx-send-to-client! (fn [id ch payload]
                                            (swap! fx* conj [id ch payload])
                                            nil)]
      (tick! {:player-id "p1" :ctx-id ctx-id :player {:uuid "p1"}}))
    (is (= [[{:uuid "p1"} "my_mod:entity_arc" 0.0]] @spawned*))
    (is (= [[ctx-id :current-charging/fx-update
         {:is-item false
          :good? true
          :charged 8.0
          :charge-ticks 6
          :target {:x 1.0 :y 2.0 :z 3.0}
          :block-pos [1 2 3]
          :source-player-id "p1"}]]
           @fx*))))

(deftest cost-fail-sends-end-and-terminates-test
  (let [ctx-id "ctx-fail"
        contexts* (mk-context-store ctx-id {:skill-state {:is-item false}})
        fx* (atom [])
        terminated* (atom [])
        cost-fail! (get (skill-actions) :cost-fail!)]
    (with-redefs [ctx/get-context (fn [id] (get @contexts* id))
                  ctx/update-context! (fn [id f & args]
                                        (swap! contexts* update id #(apply f % args))
                                        nil)
                  ctx/ctx-send-to-client! (fn [id ch payload]
                                            (swap! fx* conj [id ch payload])
                                            nil)
                  ctx/terminate-context! (fn [id _]
                                           (swap! terminated* conj id)
                                           nil)]
      (cost-fail! {:ctx-id ctx-id}))
    (is (= [[ctx-id :current-charging/fx-end {:is-item false}]] @fx*))
    (is (= [ctx-id] @terminated*))
    (is (nil? (get-in @contexts* [ctx-id :skill-state])))))
