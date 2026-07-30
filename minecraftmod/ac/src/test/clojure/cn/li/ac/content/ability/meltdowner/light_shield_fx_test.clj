(ns cn.li.ac.content.ability.meltdowner.light-shield-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.meltdowner.light-shield-fx :as ls-fx]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- reset-fixture [f]
  (runtime-hooks/with-client-ctx-fn {:session-id :test-session} (fn [] (try
          (level-effects/reset-level-effect-registry-for-test!)
          (ls-fx/reset-fx-for-test!)
          (f)
          (finally
            (ls-fx/reset-fx-for-test!)
            (level-effects/reset-level-effect-registry-for-test!))))))

(use-fixtures :each reset-fixture)

(defn- event
  [ctx-id channel payload]
  {:payload payload
   :ctx-id ctx-id
   :channel channel
   :owner-key [:ctx ctx-id]})

(deftest init-registers-owner-aware-light-shield-fx-test
  (let [registered-level* (atom nil)
        registered-topics* (atom #{})]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channel! (fn [topic _handler]
                                                      (swap! registered-topics* conj topic)
                                                      nil)]
      (ls-fx/init!)
      (is (= :light-shield (first @registered-level*)))
      (is (fn? (:enqueue-state-fn (second @registered-level*))))
      (is (= #{:light-shield/fx-start
               :light-shield/fx-end}
             @registered-topics*)))))

(deftest start-end-update-state-and-build-plan-test
  (let [
        particles* (atom [])
        sounds* (atom [])]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "light-shield-fx-test"})
                  client-particles/queue-particle-effect! (fn [& args]
                                                             (swap! particles* conj args)
                                                             nil)
                  client-sounds/queue-sound-effect! (fn [& args]
                                                        (swap! sounds* conj args)
                                                        nil)
                  ;; the spark emitter is a 30%-per-tick roll; force it on
                  rand (fn ([] 0.0) ([n] (* 0.0 n)))]
      (arc-beam/enqueue-for-test! :light-shield "ctx-ls" :light-shield/fx-start {:mode :start :source-player-id "player-a"})
      (is (some? (get-in (ls-fx/fx-snapshot) [:effect-state [:ctx "ctx-ls"]])))
      (dotimes [_ 5]
        (level-effects/update-effect-state! :light-shield
          (fn [store] (arc-beam/effect-tick-state! :level :light-shield store))))
      (is (seq @particles*))
      (is (map? (arc-beam/effect-build-plan :light-shield {:x 0.0 :y 64.0 :z 0.0} {:player-uuid "player-a" :x 0.0 :y 64.0 :z 0.0} 12)))
      (arc-beam/enqueue-for-test! :light-shield "ctx-ls" :light-shield/fx-end {:mode :end :source-player-id "player-a"})
      (is (nil? (get-in (ls-fx/fx-snapshot) [:effect-state [:ctx "ctx-ls"]])))
      (is (seq @sounds*)))))

;; Upstream rolls the spark emitter at 30% per tick rather than on a fixed
;; cadence, so the gate is pinned by driving `rand` from both sides.
(defn- light-shield-particles-over-10-ticks
  [roll]
  (let [particles* (atom [])]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "light-shield-fx-test"})
                  client-particles/queue-particle-effect! (fn [& args]
                                                            (swap! particles* conj args)
                                                            nil)
                  client-sounds/queue-sound-effect! (fn [& _] nil)
                  rand (fn ([] roll) ([n] (* roll n)))]
      (arc-beam/enqueue-for-test! :light-shield "ctx-cadence" :light-shield/fx-start {:mode :start :source-player-id "player-a"})
      (dotimes [_ 10]
        (level-effects/update-effect-state! :light-shield
          (fn [store] (arc-beam/effect-tick-state! :level :light-shield store))))
      (let [ticks (get-in (ls-fx/fx-snapshot) [:effect-state [:ctx "ctx-cadence"] :ticks])]
        (arc-beam/enqueue-for-test! :light-shield "ctx-cadence" :light-shield/fx-end {:mode :end :source-player-id "player-a"})
        {:ticks ticks
         :particles (count @particles*)
         :cleared? (nil? (get-in (ls-fx/fx-snapshot) [:effect-state [:ctx "ctx-cadence"]]))}))))

(deftest light-shield-particle-cadence-test
  (let [always (light-shield-particles-over-10-ticks 0.0)
        never (light-shield-particles-over-10-ticks 0.9)]
    (is (= 10 (:ticks always)))
    (is (= 10 (:particles always)) "one spark per tick when the roll always passes")
    (is (= 0 (:particles never)) "no sparks when the roll always fails")
    (is (true? (:cleared? always)))
    (is (true? (:cleared? never)))))
