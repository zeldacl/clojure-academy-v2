(ns cn.li.ac.content.ability.meltdowner.jet-engine-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.meltdowner.jet-engine-fx :as je-fx]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- reset-fixture [f]
  (runtime-hooks/with-client-ctx {:session-id :test-session}
    (try
          (level-effects/reset-level-effect-registry-for-test!)
          (je-fx/reset-fx-for-test!)
          (f)
          (finally
            (je-fx/reset-fx-for-test!)
            (level-effects/reset-level-effect-registry-for-test!)))))

(use-fixtures :each reset-fixture)

(defn- dispatch! [ctx-id channel payload]
  (level-effects/enqueue-level-effect! :jet-engine ctx-id channel payload :owner-key [:ctx ctx-id]))

(deftest init-registers-parity-jet-engine-fx-channels-test
  (let [registered-level* (atom nil)
        registered-topics* (atom #{})]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channel! (fn [topic _handler]
                                                      (swap! registered-topics* conj topic)
                                                      nil)]
      (je-fx/init!)
      (is (= :jet-engine (first @registered-level*)))
      (is (fn? (:enqueue-state-fn (second @registered-level*))))
      (is (= #{:jet-engine/fx-start
               :jet-engine/fx-update
               :jet-engine/fx-end
               :jet-engine/fx-trigger-start
               :jet-engine/fx-trigger-update
               :jet-engine/fx-trigger-end}
             @registered-topics*)))))

(deftest mark-and-trigger-state-flow-with-snapshot-test
  (let [
        sounds* (atom [])
        local-effects* (atom [])]
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [& args]
                                                               (swap! sounds* conj (last args))
                                                               nil)
                  client-bridge/run-client-effect! (fn [effect-key payload]
                                                     (swap! local-effects* conj [effect-key payload])
                                                     (when (= :mcmod/spawn-local-scripted-effect effect-key)
                                                       "shield-uuid-1"))]
      (je-fx/init!)
      (dispatch! "ctx-je" :jet-engine/fx-start {:mode :mark-start :target {:x 1.0 :y 64.0 :z 1.0} :hold-ticks 0})
      (is (= :marking (get-in (je-fx/fx-snapshot) [:fx-state [:ctx "ctx-je"] :phase])))
      (is (seq (:ops (arc-beam/effect-build-plan :jet-engine {:x 0.0 :y 65.0 :z 0.0} nil 0))))

      (dispatch! "ctx-je" :jet-engine/fx-trigger-start
                 {:mode :trigger-start
                  :start {:x 0.0 :y 64.0 :z 0.0}
                  :target {:x 4.0 :y 64.0 :z 0.0}
                  :pos {:x 1.0 :y 64.0 :z 0.0}
                  :trigger-ticks 0})
      (is (= :triggering (get-in (je-fx/fx-snapshot) [:fx-state [:ctx "ctx-je"] :phase])))
      (let [ops (:ops (arc-beam/effect-build-plan :jet-engine {:x 0.0 :y 65.0 :z 0.0} nil 1))]
        (is (seq ops))
        (is (some #(= :line (:kind %)) ops))
        (is (some #(= :quad (:kind %)) ops)))

      (dotimes [_ 20]
        (level-effects/tick-level-effects!))
      (is (nil? (arc-beam/effect-build-plan :jet-engine {:x 0.0 :y 65.0 :z 0.0} nil 2)))
      (is (seq @sounds*))
            (is (= [[:mcmod/spawn-local-scripted-effect {:effect-id "entity_diamond_shield"}]
              [:mcmod/remove-local-scripted-effect {:entity-uuid "shield-uuid-1"}]]
              @local-effects*)))))

(deftest trigger-start-spawns-diamond-shield-once-per-phase-entry-test
  (let [local-effects* (atom [])]
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [& _] nil)
                  client-bridge/run-client-effect! (fn [effect-key payload]
                                                     (swap! local-effects* conj [effect-key payload])
                                                     (when (= :mcmod/spawn-local-scripted-effect effect-key)
                                                       "shield-uuid-2"))]
      (je-fx/init!)
      (dispatch! "ctx-je" :jet-engine/fx-trigger-start
                 {:mode :trigger-start
                  :start {:x 0.0 :y 64.0 :z 0.0}
                  :target {:x 4.0 :y 64.0 :z 0.0}
                  :pos {:x 1.0 :y 64.0 :z 0.0}
                  :trigger-ticks 0})
      (dispatch! "ctx-je" :jet-engine/fx-trigger-start
                 {:mode :trigger-start
                  :start {:x 0.0 :y 64.0 :z 0.0}
                  :target {:x 4.0 :y 64.0 :z 0.0}
                  :pos {:x 2.0 :y 64.0 :z 0.0}
                  :trigger-ticks 1})
      (is (= [[:mcmod/spawn-local-scripted-effect {:effect-id "entity_diamond_shield"}]]
             @local-effects*)))))

(deftest trigger-end-clears-owner-state-and-explicitly-removes-diamond-shield-test
  (let [local-effects* (atom [])]
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [& _] nil)
                  client-bridge/run-client-effect! (fn [effect-key payload]
                                                     (swap! local-effects* conj [effect-key payload])
                                                     (when (= :mcmod/spawn-local-scripted-effect effect-key)
                                                       "shield-uuid-3"))]
      (je-fx/init!)
      (dispatch! "ctx-je" :jet-engine/fx-trigger-start
                 {:mode :trigger-start
                  :start {:x 0.0 :y 64.0 :z 0.0}
                  :target {:x 4.0 :y 64.0 :z 0.0}
                  :pos {:x 1.0 :y 64.0 :z 0.0}
                  :trigger-ticks 0})
      (is (contains? (set (keys (:fx-state (je-fx/fx-snapshot)))) [:ctx "ctx-je"]))
      (dispatch! "ctx-je" :jet-engine/fx-trigger-end {:mode :trigger-end})
      (is (not (contains? (set (keys (:fx-state (je-fx/fx-snapshot)))) [:ctx "ctx-je"])))
      (is (= [[:mcmod/spawn-local-scripted-effect {:effect-id "entity_diamond_shield"}]
              [:mcmod/remove-local-scripted-effect {:entity-uuid "shield-uuid-3"}]]
             @local-effects*)))))

(deftest trigger-flash-fades-with-ttl-test
  (do
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [& _] nil)
                  client-bridge/run-client-effect! (fn [effect-key _payload]
                                                     (when (= :mcmod/spawn-local-scripted-effect effect-key)
                                                       "shield-uuid-4"))]
      (je-fx/init!)
      (dispatch! "ctx-fade" :jet-engine/fx-trigger-start
                 {:mode :trigger-start
                  :start {:x 0.0 :y 64.0 :z 0.0}
                  :target {:x 4.0 :y 64.0 :z 0.0}
                  :pos {:x 1.0 :y 64.0 :z 0.0}
                  :trigger-ticks 0})

      (let [a0 (:a (last (:ops (arc-beam/effect-build-plan :jet-engine {:x 0.0 :y 65.0 :z 0.0} nil 0))))]
        (dotimes [_ 13]
          (level-effects/tick-level-effects!))
        (let [a1 (:a (last (:ops (arc-beam/effect-build-plan :jet-engine {:x 0.0 :y 65.0 :z 0.0} nil 13))))]
          (is (> a0 a1)
              "screen flash alpha should fade as trigger ttl decreases")))

      (dotimes [_ 7]
        (level-effects/tick-level-effects!))

      (is (nil? (arc-beam/effect-build-plan :jet-engine {:x 0.0 :y 65.0 :z 0.0} nil 20)))
      (is (empty? (:fx-state (je-fx/fx-snapshot)))))))
