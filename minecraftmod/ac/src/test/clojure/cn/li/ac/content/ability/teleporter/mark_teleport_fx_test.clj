(ns cn.li.ac.content.ability.teleporter.mark-teleport-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.content.ability.teleporter.mark-teleport-fx :as mfx]))

(defn- with-fresh-mark-teleport-fx-runtime [f]
  (level-effects/reset-level-effect-registry-for-test!)
  (mfx/reset-fx-for-test!)
      (try
        (f)
        (finally
          (mfx/reset-fx-for-test!)
          (level-effects/reset-level-effect-registry-for-test!))))

(use-fixtures :each with-fresh-mark-teleport-fx-runtime)

(deftest init-registers-mark-teleport-fx-channels-test
  (let [registered-level* (atom nil)
        registered-topics* (atom #{})]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channel! (fn [topic _handler]
                                                      (swap! registered-topics* conj topic)
                                                      nil)]
      (mfx/init!)
      (is (= :mark-teleport (first @registered-level*)))
      (is (= #{:mark-teleport/fx-start
               :mark-teleport/fx-update
               :mark-teleport/fx-perform
               :mark-teleport/fx-end}
             @registered-topics*)))))

(deftest enqueue-perform-plays-teleport-sound-test
  ;; Upstream s_execute -> MSG_SOUND -> c_sound: only the tp.tp sound, no
  ;; burst particles — the green ambient particles already surround the mark.
  (let [sounds* (atom [])]
    (with-redefs [client-sounds/current-effect-owner (fn [] {:client-session-id "mark-teleport-test"})
                  client-sounds/queue-sound-effect! (fn [& args]
                                                      (swap! sounds* conj args)
                                                      nil)]
      (mfx/init!)
      (level-effects/enqueue-level-effect! :mark-teleport "ctx-1" :mark-teleport/fx-perform {:mode :perform :target {:x 2.0 :y 64.0 :z 3.0} :distance 8.0}
                                         :owner-key [:ctx "ctx-1"])
      (is (= 1 (count @sounds*)))
      (is (= "academy:tp.tp" (:sound-id (second (first @sounds*))))))))

(deftest build-plan-emits-humanoid-and-ambient-particles-test
  (with-redefs [client-sounds/current-effect-owner (fn [] {:client-session-id "mark-teleport-test"})
                ;; rand-range calls (rand (- b a)) — keep both arities.
                clojure.core/rand (fn [& _] 0.0)]
    (mfx/init!)
    (level-effects/enqueue-level-effect! :mark-teleport "ctx-1" :mark-teleport/fx-start
                                         {:mode :start :target {:x 1.0 :y 64.0 :z 3.0} :distance 8.0}
                                         :owner-key [:ctx "ctx-1"])
    ;; Two ticks: the humanoid frame advances and ambient particles spawn
    ;; (rand 0.0 -> the 0.4 gate always passes).
    (level-effects/tick-level-effects!)
    (level-effects/tick-level-effects!)
    (let [{:keys [ops]} (arc-beam/effect-build-plan
                         :mark-teleport {:x 0.0 :y 0.0 :z 0.0}
                         {:player-uuid "viewer"} 0 nil)]
      ;; 6 ModelBiped parts x 6 faces + 2 ambient particles (2 ticks, rand 0).
      (is (<= 38 (count ops)))
      (is (every? #(= :quad (:kind %)) ops))
      ;; The humanoid is built from tp_mark frame quads (upstream MarkRender
      ;; effect sequence); its silhouette spans feet y=64 (destination) to
      ;; head top ~65.9 (ModelBiped 1.8 tall standing on the mark).
      (let [humanoid (filter #(re-find #"^academy:textures/effects/tp_mark/\d+\.png$"
                                       (str (:texture %)))
                             ops)
            ys (mapcat (fn [op]
                         (let [^cn.li.mcmod.math.V3 p1 (:p1 op)
                               ^cn.li.mcmod.math.V3 p2 (:p2 op)
                               ^cn.li.mcmod.math.V3 p3 (:p3 op)]
                           [(.y p1) (.y p2) (.y p3)]))
                       humanoid)]
        (is (= 36 (count humanoid)))
        (is (some #(<= (Math/abs (- % 64.0)) 0.001) ys)
            "feet on the destination")
        (is (some #(>= % 65.8) ys)
            "head reaches the model top"))
      ;; Ambient green TPParticle particles at the mark.
      (is (some #(re-find #"^academy:textures/effects/tp_particle\.png$"
                          (str (:texture %)))
                ops)))))

(deftest perform-clears-mark-state-test
  (with-redefs [client-sounds/current-effect-owner (fn [] {:client-session-id "mark-teleport-test"})
                client-sounds/queue-sound-effect! (fn [& _] nil)]
    (mfx/init!)
    (level-effects/enqueue-level-effect! :mark-teleport "ctx-1" :mark-teleport/fx-start
                                         {:mode :start :target {:x 1.0 :y 2.0 :z 3.0} :distance 8.0}
                                         :owner-key [:ctx "ctx-1"])
    (is (some? (get (:effect-state (mfx/fx-snapshot)) [:ctx "ctx-1"])))
    (level-effects/enqueue-level-effect! :mark-teleport "ctx-1" :mark-teleport/fx-perform
                                         {:mode :perform :target {:x 1.0 :y 2.0 :z 3.0} :distance 8.0}
                                         :owner-key [:ctx "ctx-1"])
    ;; Upstream l_end kills the mark on MSG_TERMINATED.
    (is (nil? (get (:effect-state (mfx/fx-snapshot)) [:ctx "ctx-1"])))))

(deftest enqueue-end-clears-state-test
  (with-redefs [client-sounds/current-effect-owner (fn [] {:client-session-id "mark-teleport-test"})]
    (mfx/init!)
    (level-effects/enqueue-level-effect! :mark-teleport "ctx-1" :mark-teleport/fx-start {:mode :start}
                                         :owner-key [:ctx "ctx-1"])
    (level-effects/enqueue-level-effect! :mark-teleport "ctx-1" :mark-teleport/fx-update {:mode :update :target {:x 1.0 :y 2.0 :z 3.0} :distance 2.0}
                                         :owner-key [:ctx "ctx-1"])
    (is (some? (get (:effect-state (mfx/fx-snapshot)) [:ctx "ctx-1"])))
    (level-effects/enqueue-level-effect! :mark-teleport "ctx-1" :mark-teleport/fx-end {:mode :end}
                                         :owner-key [:ctx "ctx-1"])
    (is (nil? (get (:effect-state (mfx/fx-snapshot)) [:ctx "ctx-1"])))))



(deftest fx-snapshot-default-without-registered-state-test
  ;; clojure.test runs deftests in ns-interns hash order, not declaration
  ;; order — this test may run before any other init! call, so register the
  ;; effect (and load the impl defmethods) here instead of relying on order.
  (mfx/init!)
  (is (= {:effect-state {}}
         (mfx/fx-snapshot))))
