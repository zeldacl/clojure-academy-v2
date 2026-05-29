(ns cn.li.ac.content.ability.meltdowner.jet-engine-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.meltdowner.jet-engine-fx :as je-fx]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- reset-fixture [f]
  (binding [runtime-hooks/*client-session-id* :test-session]
    (level-effects/call-with-level-effect-runtime
      (level-effects/create-level-effect-runtime)
      (fn []
        (try
          (level-effects/reset-level-effect-registry-for-test!)
          (je-fx/reset-jet-engine-fx-for-test!)
          (f)
          (finally
            (je-fx/reset-jet-engine-fx-for-test!)
            (level-effects/reset-level-effect-registry-for-test!)))))))

(use-fixtures :each reset-fixture)

(defn- event
  [ctx-id channel payload]
  {:payload payload
   :ctx-id ctx-id
   :channel channel
   :owner-key [:ctx ctx-id]})

(deftest init-registers-owner-aware-jet-engine-fx-test
  (let [registered-level* (atom nil)
        registered-handler* (atom nil)]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channels! (fn [channels handler]
                                                      (reset! registered-handler* {:channels channels
                                                                                   :handler handler})
                                                      nil)]
      (je-fx/init!)
      (is (= :jet-engine (first @registered-level*)))
      (is (fn? (:enqueue-state-fn (second @registered-level*))))
      (is (= #{:jet-engine/fx-start
               :jet-engine/fx-launch
               :jet-engine/fx-charge-max}
             (set (:channels @registered-handler*)))))))

(deftest launch-adds-state-and-builds-plan-test
  (let [enqueue-state! (var-get #'cn.li.ac.content.ability.meltdowner.jet-engine-fx/enqueue-state!)
        tick-state! (var-get #'cn.li.ac.content.ability.meltdowner.jet-engine-fx/tick-state!)
        build-plan (var-get #'cn.li.ac.content.ability.meltdowner.jet-engine-fx/build-plan)
        sounds* (atom [])]
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [& args]
                                                               (swap! sounds* conj (last args))
                                                               nil)]
      (level-effects/update-effect-state! :jet-engine
        enqueue-state!
        (event "ctx-je" :jet-engine/fx-launch {:mode :launch
                                                :speed 1.5
                                                :dx 0.0 :dy 1.0 :dz 0.0
                                                :source-player-id "player-a"}))
      (is (some? (get-in (je-fx/jet-engine-fx-snapshot) [:fx-state [:ctx "ctx-je"]])))
      (is (seq (:ops (build-plan {:x 0.0 :y 65.0 :z 0.0} nil 0))))
      (dotimes [_ 20]
        (level-effects/update-effect-state! :jet-engine
          (fn [store _]
            (tick-state! store))
          nil))
      (is (nil? (build-plan {:x 0.0 :y 65.0 :z 0.0} nil 0)))
      (is (seq @sounds*)))))

(deftest jet-engine-fx-runtime-isolation-test
  (let [runtime-a (level-effects/create-level-effect-runtime)
        runtime-b (level-effects/create-level-effect-runtime)
        enqueue-state! (var-get #'cn.li.ac.content.ability.meltdowner.jet-engine-fx/enqueue-state!)]
    (with-redefs [client-sounds/queue-current-sound-effect! (fn [& _] nil)]
      (level-effects/call-with-level-effect-runtime
        runtime-a
        (fn []
          (level-effects/update-effect-state! :jet-engine
            enqueue-state!
            (event "ctx-a" :jet-engine/fx-launch {:mode :launch
                                                    :speed 1.5
                                                    :dx 0.0 :dy 1.0 :dz 0.0
                                                    :source-player-id "player-a"}))
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:fx-state (je-fx/jet-engine-fx-snapshot))))))))
      (level-effects/call-with-level-effect-runtime
        runtime-b
        (fn []
          (is (= {:fx-state {}}
                 (je-fx/jet-engine-fx-snapshot)))
          (level-effects/update-effect-state! :jet-engine
            enqueue-state!
            (event "ctx-b" :jet-engine/fx-launch {:mode :launch
                                                    :speed 1.5
                                                    :dx 0.0 :dy 1.0 :dz 0.0
                                                    :source-player-id "player-b"}))
          (is (= #{[:ctx "ctx-b"]}
                 (set (keys (:fx-state (je-fx/jet-engine-fx-snapshot))))))))
      (level-effects/call-with-level-effect-runtime
        runtime-a
        (fn []
          (is (= #{[:ctx "ctx-a"]}
                 (set (keys (:fx-state (je-fx/jet-engine-fx-snapshot)))))))))))
