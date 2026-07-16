(ns cn.li.ac.content.ability.electromaster.current-charging-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.test.skill-callback-test-helpers :as cb]
            [cn.li.ac.ability.fx :as fx]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
            [cn.li.ac.content.ability.electromaster.current-charging :as current-charging]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.ac.test.support.player-state :as ps-fix]
            [cn.li.ac.test.support.skill-context :as skill-ctx]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.runtime-interop :as interop]))

(defn- skill-actions []
  (:actions (var-get (ns-resolve 'cn.li.ac.content.ability.electromaster.current-charging
                                 'current_charging_skill))))

(defn- skill-def []
  (var-get (ns-resolve 'cn.li.ac.content.ability.electromaster.current-charging
                       'current_charging_skill)))

(defn- context-mocks
  [ctx-id seed & {:keys [terminate-calls*]}]
  (let [contexts* (atom {ctx-id seed})
        get-context (fn
                      ([id] (get @contexts* id))
                      ([_owner id] (get @contexts* id)))
        update-skill-state-root! (fn [id f & args]
                                   (swap! contexts* update id
                                          (fn [ctx-data]
                                            (let [current (or (:skill-state ctx-data) {})
                                                  next-state (if (and (= f identity) (= 1 (count args)))
                                                               (first args)
                                                               (apply f current args))]
                                              (assoc ctx-data :skill-state next-state))))
                                   nil)
        assoc-skill-state! (fn [id k v]
                             (swap! contexts* update id
                                    (fn [ctx-data]
                                      (let [path (if (vector? k) k [k])]
                                        (update ctx-data :skill-state #(assoc-in (or % {}) path v)))))
                             nil)
        clear-skill-state! (fn [id]
                             (swap! contexts* update id dissoc :skill-state)
                             nil)
        terminate-context! (fn
                             ([id _]
                              (when terminate-calls*
                                (swap! terminate-calls* conj id))
                              nil)
                             ([_owner id _]
                              (when terminate-calls*
                                (swap! terminate-calls* conj id))
                              nil))]
    {:contexts* contexts*
     :ctx-id ctx-id
     :get-context get-context
     :update-skill-state-root! update-skill-state-root!
     :assoc-skill-state! assoc-skill-state!
     :clear-skill-state! clear-skill-state!
     :terminate-context! terminate-context!}))

(defn- with-charging-env [f]
  (skill-ctx/with-server-skill-context f))

(use-fixtures :each (fn [f]
                      (ps-fix/with-test-player-state-owner
                        #(with-charging-env f))))

(deftest pattern-is-hold-channel-test
  (is (= :hold-channel (:pattern (skill-def)))))

(deftest down-action-initializes-state-and-starts-fx-test
  (let [ctx-id "ctx-1"
        {:keys [contexts* get-context update-skill-state-root! assoc-skill-state!
                clear-skill-state! terminate-context!]}
        (context-mocks ctx-id {})
        fx* (atom [])
        down! (get (skill-actions) :down!)]
    (with-redefs [current-charging/main-hand-item (fn [_] :stack)
                  current-charging/cfg-lerp (fn [_ _] 52.0)
                  ctx/get-context get-context
                  ctx/terminate-context! terminate-context!
                  ctx-skill/update-skill-state-root! update-skill-state-root!
                  ctx-skill/assoc-skill-state! assoc-skill-state!
                  ctx-skill/clear-skill-state! clear-skill-state!
                  fx/send! (fn [id entry _evt payload]
                             (swap! fx* conj [id (:topic entry) payload])
                             nil)]
      (cb/apply-invoke down! :player-id "p1" :ctx-id ctx-id :exp 0.4))
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
        {:keys [contexts* get-context update-skill-state-root! assoc-skill-state!
                clear-skill-state! terminate-context!]}
        (context-mocks ctx-id {:skill-state {:mode :item
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
                  ctx/get-context get-context
                  ctx/terminate-context! terminate-context!
                  ctx-skill/update-skill-state-root! update-skill-state-root!
                  ctx-skill/assoc-skill-state! assoc-skill-state!
                  ctx-skill/clear-skill-state! clear-skill-state!
                  fx/send! (fn [id entry _evt payload]
                             (swap! fx* conj [id (:topic entry) payload])
                             nil)]
      (cb/apply-invoke tick! :player-id "p1" :ctx-id ctx-id))
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
        {:keys [contexts* get-context update-skill-state-root! assoc-skill-state!
                clear-skill-state! terminate-context!]}
        (context-mocks ctx-id {:skill-state {:mode :block
                                              :is-item false
                                              :exp 0.5
                                              :charge-ticks 5
                                              :overload-floor 50.0}})
        fx* (atom [])
        spawned* (atom [])
        tick! (get (skill-actions) :tick!)]
    (with-redefs [current-charging/cfg-lerp (fn [_ _] 20.0)
                  current-charging/cfg-double (fn [k]
                                               (case k
                                                 :targeting.range 5.0
                                                 :progression.exp-effective 0.0001
                                                 0.0))
                  interop/available? (constantly true)
                  raycast/available? (constantly true)
                  raycast/raycast-blocks* (fn [_ _ _ _ _ _ _ _]
                                            {:x 1 :y 2 :z 3 :distance 2.0})
                  interop/get-block-entity-at* (fn [_ _ _ _] :block-entity)
                  energy/is-node-supported? (fn [_] true)
                  energy/charge-node (fn [_ charge _sim?] (- charge 8.0))
                  skill-effects/add-skill-exp! (fn [& _] nil)
                  skill-effects/enforce-overload-floor! (fn [& _] nil)
                  interop/get-player-view* (fn [_]
                                             {:world-id "minecraft:overworld"
                                              :x 0.0 :y 64.0 :z 0.0
                                              :look-x 1.0 :look-y 0.0 :look-z 0.0})
                  entity/player-spawn-entity-by-id! (fn [player eid yaw]
                                                      (swap! spawned* conj [player eid yaw])
                                                      true)
                  ctx/get-context get-context
                  ctx/terminate-context! terminate-context!
                  ctx-skill/update-skill-state-root! update-skill-state-root!
                  ctx-skill/assoc-skill-state! assoc-skill-state!
                  ctx-skill/clear-skill-state! clear-skill-state!
                  fx/send! (fn [id entry _evt payload]
                             (swap! fx* conj [id (:topic entry) payload])
                             nil)]
      (cb/apply-invoke tick! :player-id "p1" :ctx-id ctx-id :player-ref {:uuid "p1"}))
    (is (= [[{:uuid "p1"} "my_mod:entity_charging_arc" 0.0]] @spawned*))
    (is (= [[ctx-id :current-charging/fx-update
             {:is-item false
              :good? true
              :charged 8.0
              :charge-ticks 6
              :target {:x 2.0 :y 64.0 :z 0.0}
              :block-pos [1 2 3]
              :source-player-id "p1"}]]
           @fx*))))

(deftest cost-fail-sends-end-and-terminates-test
  (let [ctx-id "ctx-fail"
        terminated* (atom [])
        {:keys [contexts* get-context update-skill-state-root! assoc-skill-state!
                clear-skill-state! terminate-context!]}
        (context-mocks ctx-id {:skill-state {:is-item false}}
                       :terminate-calls* terminated*)
        fx* (atom [])
        cost-fail! (get (skill-actions) :cost-fail!)]
    (with-redefs [ctx/get-context get-context
                  ctx/terminate-context! terminate-context!
                  ctx-skill/update-skill-state-root! update-skill-state-root!
                  ctx-skill/assoc-skill-state! assoc-skill-state!
                  ctx-skill/clear-skill-state! clear-skill-state!
                  fx/send! (fn [id entry _evt payload]
                             (swap! fx* conj [id (:topic entry) payload])
                             nil)]
      (cb/apply-invoke cost-fail! :ctx-id ctx-id))
    (is (= [[ctx-id :current-charging/fx-end {:is-item false}]] @fx*))
    (is (= [ctx-id] @terminated*))
    (is (nil? (get-in @contexts* [ctx-id :skill-state])))))
