(ns cn.li.ac.content.ability.electromaster.railgun-behavior-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.ac.ability.item-actions :as item-actions]
            [cn.li.ac.ability.effects.beam :as beam]
            [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.ac.ability.fx :as fx]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
            [cn.li.ac.test.support.player-state :as ps-fix]
            [cn.li.ac.content.ability.electromaster.railgun :as railgun]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.util.log :as log]))

(defn- reset-state! [f]
  (ps-fix/with-test-player-state-owner
    (fn []
      (let [context-registry-val (ctx/snapshot-context-registry)
            item-actions-snapshot (item-actions/item-action-registries-snapshot)]
        (try
          (store/reset-store!)
          (ctx/reset-contexts-for-test!)
          (item-actions/reset-item-action-registries-for-test!)
          (f)
          (finally
            (store/reset-store!)
            (ctx/reset-contexts-for-test! context-registry-val)
            (item-actions/reset-item-action-registries-for-test! item-actions-snapshot)))))))

(use-fixtures :each reset-state!)

(deftest beam-uses-trace-origin-but-keeps-visual-origin-test
  (let [calls (atom [])]
    (with-redefs [world-effects/available? (constantly true)
                  world-effects/find-entities-in-radius
                  (fn [& args]
                    (swap! calls conj [:search args])
                    [{:uuid "e-1" :x 3.0 :y 2.0 :z 3.0}])
                  entity-damage/available? (constantly true)
                  entity-damage/apply-direct-damage! (fn [& _]
                                                       (swap! calls conj [:damage]))
                  fx/send! (fn [_ctx-id entry _evt payload]
                             (swap! calls conj [:fx (:topic entry) payload])
                             nil)]
      (let [evt {:ctx-id "ctx-1"
                 :player-id "p1"
                 :world-id "w1"
                 :eye-pos {:x 10.0 :y 20.0 :z 30.0}
                 :look-dir {:dx 0.0 :dy 0.0 :dz 1.0}}
            out (beam/execute-beam! evt {:trace-pos {:x 1.0 :y 2.0 :z 3.0}
                                         :radius 1.0
                                         :query-radius 10.0
                                         :step 0.9
                                         :max-distance 8.0
                                         :visual-distance 5.0
                                         :damage 12.0
                                         :block-energy 0.0
                                         :break-blocks? false
                                         :fx-topic :railgun/fx-shot})]
        (is (true? (get-in out [:beam-result :performed?])))
        (is (= ["w1" 1.0 2.0 3.0 10.0] (vec (second (first @calls)))))
        (is (= [:fx :railgun/fx-shot {:start {:x 10.0 :y 20.0 :z 30.0}
                                      :end {:x 10.0 :y 20.0 :z 35.0}
                                      :hit-distance 5.0}]
               (second @calls)))
        (is (not-any? #(= :damage (first %)) @calls))))))

(deftest coin-throw-aborts-item-charge-and-opens-window-test
  (ps-fix/seed-player-state! "p1" (store/fresh-player-state))
  (let [owner {:logical-side :server :server-session-id :test-session :player-uuid "p1"}]
    (ctx/with-context-owner owner
      (ctx/register-context!
       (assoc (ctx/new-server-context "p1" :railgun "ctx-1" owner)
              :status ctx/STATUS-ALIVE))
      (ctx-skill/update-skill-state-root! "ctx-1" identity
                                          {:mode :item-charge :charge-ticks 3 :fired false})
      (with-redefs [log/debug (fn [& _])]
        (is (true? (railgun/register-coin-throw! "p1" {:timestamp-ms 12345})))
        (is (= :item-charge-cancelled (get-in (ctx/get-context "ctx-1") [:skill-state :mode])))))))

(deftest coin-progress-threshold-status-test
  (let [below (#'railgun/qte-status 0.59)
        active (#'railgun/qte-status 0.6)
        edge (#'railgun/qte-status 0.7)
        perform (#'railgun/qte-status 0.71)]
    (is (true? (:has-window? below)))
    (is (false? (:active? below)))
    (is (false? (:perform? below)))

    (is (true? (:active? active)))
    (is (false? (:perform? active)))

    (is (true? (:active? edge)))
    (is (false? (:perform? edge)))

    (is (true? (:active? perform)))
    (is (true? (:perform? perform)))))

(deftest reflected-shot-excludes-reflector-and-respects-blocks-test
  (let [damage-calls* (atom [])
        fx-calls* (atom [])]
    (with-redefs [raycast/available? (constantly true)
                  raycast/player-look-vector
                  (fn [_] {:x 1.0 :y 0.0 :z 0.0})
                  raycast/raycast-from-player
                  (fn [player-id distance living-only?]
                    (is (= "reflector" player-id))
                    (is (= 15.0 distance))
                    (is (true? living-only?))
                    {:entity-id "target" :distance 5.0})
                  raycast/raycast-blocks
                  (fn [& _] {:distance 3.0})
                  geom/eye-pos (fn [_] {:x 1.0 :y 2.0 :z 3.0})
                  geom/world-id-of (fn [_] "w1")
                  entity-damage/available? (constantly true)
                  entity-damage/apply-direct-damage!
                  (fn [& args] (swap! damage-calls* conj args))
                  fx/send-local-and-nearby!
                  (fn [& args] (swap! fx-calls* conj args))
                  railgun/reflection-distance (constantly 15.0)
                  railgun/reflection-damage (constantly 14.0)]
      (is (nil? (#'railgun/perform-reflection-shot! "ctx" "reflector")))
      (is (empty? @damage-calls*))
      (is (= {:x 16.0 :y 2.0 :z 3.0}
             (:end (last (first @fx-calls*)))))

      (with-redefs [raycast/raycast-blocks (fn [& _] nil)
                    railgun/railgun-damage
                    (fn [_player _target raw] raw)]
        (is (true? (#'railgun/perform-reflection-shot! "ctx" "reflector")))
        (is (= ["w1" "target" 14.0 :generic]
               (vec (first @damage-calls*))))))))

(deftest read-coin-qte-status-skips-already-judged-coin-test
  (ps-fix/seed-player-state! "p1" (store/fresh-player-state))
  (railgun/register-coin-throw! "p1" {:timestamp-ms 42})
  (store/update-player-state! ps-fix/test-session-id "p1" assoc-in [:runtime :railgun :coin-judged-uuid] "coin-1")
  (with-redefs [world-effects/available? (constantly true)
                world-effects/find-entities-in-radius (fn [& _]
                                                         [{:uuid "coin-1"
                                                           :motion-progress 0.95}])
                railgun/coin-candidates (fn [_world-id _entities]
                                          [{:uuid "coin-1"
                                            :motion-progress 0.95}])]
    (let [status (#'railgun/read-coin-qte-status "p1")]
      (is (false? (:has-window? status)))
      (is (false? (:perform? status))))))
