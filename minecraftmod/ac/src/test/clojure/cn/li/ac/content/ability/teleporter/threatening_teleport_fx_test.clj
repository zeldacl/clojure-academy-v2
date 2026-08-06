(ns cn.li.ac.content.ability.teleporter.threatening-teleport-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.content.ability.teleporter.threatening-teleport-fx :as tfx]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(defn- with-fresh-threatening-teleport-fx-runtime [f]
  (level-effects/reset-level-effect-registry-for-test!)
  (tfx/reset-fx-for-test!)
      (try
        (f)
        (finally
          (tfx/reset-fx-for-test!)
          (level-effects/reset-level-effect-registry-for-test!))))

(use-fixtures :each with-fresh-threatening-teleport-fx-runtime)

(defn- enqueue! [ctx-id channel payload]
  (level-effects/enqueue-level-effect! :threatening-teleport ctx-id channel payload
                                       :owner-key [:ctx ctx-id]))

(defn- build-plan []
  (arc-beam/effect-build-plan :threatening-teleport nil {:player-uuid "viewer"} 0 nil))

(defn- with-fx-owner [f]
  (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "tt-test"})]
    (f)))

(deftest init-registers-threatening-teleport-fx-channels-test
  (let [registered-level* (atom nil)
        registered-topics* (atom #{})]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channel! (fn [topic _handler]
                                                      (swap! registered-topics* conj topic)
                                                      nil)]
      (tfx/init!)
      (is (= :threatening-teleport (first @registered-level*)))
      (is (= #{:threatening-teleport/fx-start
               :threatening-teleport/fx-update
               :threatening-teleport/fx-perform
               :threatening-teleport/fx-end}
             @registered-topics*)))))

(deftest two-owners-keep-threatening-teleport-state-independent-test
  (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "threatening-teleport-test"})]
    (tfx/init!)
    (enqueue! "ctx-a" :threatening-teleport/fx-start {:mode :start :target-x 1.0 :target-y 2.0 :target-z 3.0 :hit? true})
    (enqueue! "ctx-b" :threatening-teleport/fx-start {:mode :start :target-x 4.0 :target-y 5.0 :target-z 6.0 :hit? false})
    (enqueue! "ctx-a" :threatening-teleport/fx-update {:mode :update :target-x 1.0 :target-y 2.0 :target-z 3.0 :hit? true})
    (enqueue! "ctx-b" :threatening-teleport/fx-update {:mode :update :target-x 4.0 :target-y 5.0 :target-z 6.0 :hit? false})
    (let [snapshot (tfx/fx-snapshot)]
      (is (true? (:hit? (get (:fx-state snapshot) [:ctx "ctx-a"]))))
      (is (= {:x 4.0 :y 5.0 :z 6.0}
             (:aim (get (:fx-state snapshot) [:ctx "ctx-b"])))))
    (enqueue! "ctx-a" :threatening-teleport/fx-end {:mode :end})
    (let [snapshot (tfx/fx-snapshot)]
      (is (nil? (get (:fx-state snapshot) [:ctx "ctx-a"])))
      (is (some? (get (:fx-state snapshot) [:ctx "ctx-b"]))))
    (tfx/clear-fx-owner! [:ctx "ctx-b"])
    (is (empty? (:fx-state (tfx/fx-snapshot))))))

(deftest fx-snapshot-default-without-registered-state-test
  (is (= {:fx-state {}}
         (tfx/fx-snapshot))))

(deftest start-holds-aim-from-trace-payload-test
  (with-fx-owner
    (fn []
      (tfx/init!)
      ;; :start carries the first trace so the marker appears the moment the key
      ;; goes down (upstream l_start spawns the marker on MSG_MADEALIVE).
      (enqueue! "ctx-s" :threatening-teleport/fx-start
                {:mode :start :target-x 7.0 :target-y 8.0 :target-z 9.0 :hit? false})
      (let [st (get (:fx-state (tfx/fx-snapshot)) [:ctx "ctx-s"])]
        (is (true? (:active? st)))
        (is (= {:x 7.0 :y 8.0 :z 9.0} (:aim st)))))))

(deftest marker-sits-at-target-feet-when-tracking-test
  ;; Upstream l_tick subtracts the target height: the marker hovers at the
  ;; target's FEET while the drop point is the top of the box.
  (with-fx-owner
    (fn []
      (tfx/init!)
      (enqueue! "ctx-ft" :threatening-teleport/fx-start
                {:mode :start :target-x 4.0 :target-y 8.0 :target-z 6.0 :hit? true :target-height 1.95})
      (let [st (get (:fx-state (tfx/fx-snapshot)) [:ctx "ctx-ft"])]
        (is (= 6.05 (:y (:aim st))))))))

(deftest build-plan-emits-marker-cube-at-aim-test
  ;; No state -> no ops.
  (is (nil? (build-plan)))
  (with-fx-owner
    (fn []
      (tfx/init!)
      (enqueue! "ctx-p" :threatening-teleport/fx-update
                {:mode :update :target-x 10.0 :target-y 11.0 :target-z 12.0 :hit? true})))
  (let [{:keys [ops]} (build-plan)]
    ;; Wireframe cube: 12 edges centered on the aim point.
    (is (= 12 (count ops)))
    (is (every? #(= :line (:kind %)) ops))
    (let [centers (map (fn [op]
                         (let [^cn.li.mcmod.math.V3 p1 (:p1 op)]
                           [(.x p1) (.y p1) (.z p1)]))
                       ops)]
      (is (every? (fn [[x y z]]
                    (and (< (Math/abs (- x 10.0)) 0.3)
                         (< (Math/abs (- y 11.0)) 0.3)
                         (< (Math/abs (- z 12.0)) 0.3)))
                  centers))
      ;; Threatening color when targeting an entity (upstream COLOR_THREATENING).
      (is (= {:r 0xba :g 0xb2 :b 0x23 :a 0x2a} (:color (first ops)))))))

(deftest build-plan-uses-normal-color-without-target-test
  (with-fx-owner
    (fn []
      (tfx/init!)
      (enqueue! "ctx-g" :threatening-teleport/fx-update
                {:mode :update :target-x 1.0 :target-y 2.0 :target-z 3.0 :hit? false})))
  (let [{:keys [ops]} (build-plan)]
    (is (= 12 (count ops)))
    (is (= {:r 0xba :g 0xba :b 0xba :a 0xba} (:color (first ops))))))

(deftest perform-clears-marker-and-plays-attack-fx-test
  (let [particles* (atom [])
        sounds* (atom [])]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "tt-test"})
                  client-particles/queue-particle-effect! (fn [owner fx]
                                                            (swap! particles* conj fx)
                                                            nil)
                  client-sounds/queue-sound-effect! (fn [owner fx]
                                                      (swap! sounds* conj fx)
                                                      nil)]
      (tfx/init!)
      (enqueue! "ctx-f" :threatening-teleport/fx-start
                {:mode :start :target-x 4.0 :target-y 5.0 :target-z 6.0 :hit? true})
      (is (some? (get (:fx-state (tfx/fx-snapshot)) [:ctx "ctx-f"])))
      (enqueue! "ctx-f" :threatening-teleport/fx-perform
                {:mode :perform
                 :start-x 1.0 :start-y 2.0 :start-z 3.0
                 :target-x 4.0 :target-y 5.0 :target-z 6.0
                 :hit? true})
      ;; Marker dies on execute (upstream c_end marker.setDead).
      (is (nil? (get (:fx-state (tfx/fx-snapshot)) [:ctx "ctx-f"])))
      ;; Hit cue burst + portal trail + tp sound.
      (is (some? (some #(and (= :particle (:type %)) (= 8 (:count %))) @particles*)))
      (is (pos? (count (filter #(= 1 (:count %)) @particles*))))
      (is (= 1 (count @sounds*)))
      (is (= (modid/namespaced-path "tp.tp") (:sound-id (first @sounds*)))))))
