(ns cn.li.ac.content.ability.electromaster.mag-movement-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.client.effect-controller :as vfx-level]
            [cn.li.ac.content.ability.electromaster.mag-movement-fx :as mag-movement-fx]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- invoke-level-enqueue! [ctx-id channel payload]
  (arc-beam/enqueue-for-test! :mag-movement ctx-id channel payload))

(defn- invoke-tick! []
  (vfx-level/update-effect-state! :mag-movement
    (fn [store] (arc-beam/effect-tick-state! :level :mag-movement store))))

(defn- reset-fixture [f]
  (runtime-hooks/with-client-ctx-fn {:session-id :test-session} (fn [] (try
      (vfx-level/reset-level-effect-registry-for-test!)
      (mag-movement-fx/reset-fx-for-test!)
      (mag-movement-fx/init!)
      (client-sounds/poll-sound-effects!)
      (f)
      (finally
        (mag-movement-fx/reset-fx-for-test!)
        (client-sounds/poll-sound-effects!)
        (vfx-level/reset-level-effect-registry-for-test!))))))

(use-fixtures :each reset-fixture)

(deftest init-registers-mag-movement-fx-channels-test
  (let [registered-effect* (atom nil)
        registered-topics* (atom #{})]
    (with-redefs [vfx-level/register-level-effect! (fn [effect-id effect-map]
                                                          (reset! registered-effect* [effect-id effect-map])
                                                          nil)
                  fx-registry/register-fx-channel! (fn [topic _handler]
                                                      (swap! registered-topics* conj topic)
                                                      nil)]
      (mag-movement-fx/init!)
      (is (= :mag-movement (first @registered-effect*)))
      (is (= #{:mag-movement/fx-start :mag-movement/fx-update :mag-movement/fx-end}
             @registered-topics*)))))

(deftest fx-handler-routes-start-update-end-test
  (let [handlers* (atom {})
        enqueued* (atom [])]
    (with-redefs [vfx-level/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channel! (fn [topic handler]
                                                      (swap! handlers* assoc topic handler)
                                                      nil)
                  vfx-level/enqueue-level-effect! (fn [effect-id ctx-id channel payload & opts]
                                                        (swap! enqueued* conj (into [effect-id ctx-id channel payload] opts))
                                                        nil)]
      (mag-movement-fx/init!)
      ((get @handlers* :mag-movement/fx-start) "ctx" :mag-movement/fx-start {:target {:x 1.0 :y 2.0 :z 3.0}})
      ((get @handlers* :mag-movement/fx-update) "ctx" :mag-movement/fx-update {:target {:x 2.0 :y 3.0 :z 4.0}})
      ((get @handlers* :mag-movement/fx-end) "ctx" :mag-movement/fx-end {})
      (is (= 3 (count @enqueued*)))
      (let [[effect-id ctx-id channel payload & opts] (first @enqueued*)]
        (is (= :mag-movement effect-id))
        (is (= :start (:mode payload)))
        (is (= {:x 1.0 :y 2.0 :z 3.0} (:target payload)))
        (is (= "ctx" ctx-id))
        (is (= :mag-movement/fx-start channel))
        (is (= [:owner-key [:ctx "ctx"]] opts)))
      (is (= :update (:mode (nth (second @enqueued*) 3))))
      (is (= :end (:mode (nth (nth @enqueued* 2) 3)))))))

(deftest enqueue-and-end-are-idempotent-test
  (invoke-level-enqueue! "ctx-main" :mag-movement/fx-start {:mode :start :target {:x 1.0 :y 2.0 :z 3.0}})
  (invoke-level-enqueue! "ctx-main" :mag-movement/fx-update {:mode :update :target {:x 4.0 :y 5.0 :z 6.0}})
  (is (= {:active? true :target {:x 4.0 :y 5.0 :z 6.0} :ticks 0}
         (select-keys (get (:effect-state (mag-movement-fx/fx-snapshot)) [:ctx "ctx-main"])
                      [:active? :target :ticks])))
  (invoke-level-enqueue! "ctx-main" :mag-movement/fx-end {:mode :end})
  (invoke-level-enqueue! "ctx-main" :mag-movement/fx-end {:mode :end})
  (is (nil? (get (:effect-state (mag-movement-fx/fx-snapshot)) [:ctx "ctx-main"]))))

(deftest loop-sound-runs-for-the-skill-and-stops-with-it-test
  ;; Original c_startEffect starts one FollowEntitySound(player, SOUND).setLoop()
  ;; and c_endEffect stops it. Re-queuing em.move_loop as one-shots every 10
  ;; ticks instead left the last sample playing past the end of the skill with
  ;; no handle to stop it — which is exactly what a loop sample does.
  (let [effects* (atom [])
        sounds* (atom [])]
    (with-redefs [client-bridge/run-client-effect! (fn [op payload]
                                                     (swap! effects* conj [op (:key payload) (:sound-id payload)])
                                                     nil)
                  client-sounds/queue-sound-effect! (fn [_owner payload]
                                                      (swap! sounds* conj payload)
                                                      nil)]
      (invoke-level-enqueue! "ctx-main" :mag-movement/fx-start
        {:mode :start :source-player-id "player-a" :target {:x 1.0 :y 2.0 :z 3.0}})
      (is (= [[:mcmod/start-loop-sound-at-player "mag-movement/ctx-main" "academy:em.move_loop"]]
             @effects*))
      (dotimes [_ 30] (invoke-tick!))
      (is (= 1 (count @effects*)) "ticking never re-triggers the sound")
      (is (empty? @sounds*) "and nothing goes through the one-shot queue")
      (invoke-level-enqueue! "ctx-main" :mag-movement/fx-end {:mode :end})
      (is (= [:mcmod/stop-loop-sound "mag-movement/ctx-main"]
             (take 2 (last @effects*)))))))

(deftest externally-aborted-context-also-stops-the-loop-test
  ;; Contexts killed from outside (overload stun, death) never get :end.
  (let [effects* (atom [])]
    (with-redefs [client-bridge/run-client-effect! (fn [op payload]
                                                     (swap! effects* conj [op (:key payload)])
                                                     nil)]
      (invoke-level-enqueue! "ctx-abort" :mag-movement/fx-start
        {:mode :start :source-player-id "player-a" :target {:x 1.0 :y 2.0 :z 3.0}})
      (mag-movement-fx/clear-fx-owner! [:ctx "ctx-abort"])
      (is (= [:mcmod/stop-loop-sound "mag-movement/ctx-abort"] (last @effects*))))))

(deftest arc-renders-at-full-brightness-test
  ;; life-fade-alpha fades out over the last 20% of life, so the previous
  ;; :life-ratio 1.0 multiplied every colour by alpha 0 and the arc was emitted
  ;; completely transparent. Upstream's thinContiniousArc lives as long as the
  ;; skill and never fades.
  (with-redefs [client-bridge/run-client-effect! (fn [& _] nil)]
    (invoke-level-enqueue! "ctx-vis" :mag-movement/fx-start
      {:mode :start :source-player-id "player-a"})
    (invoke-level-enqueue! "ctx-vis" :mag-movement/fx-update
      {:mode :update :source-player-id "player-a" :target {:x 5.0 :y 66.0 :z 5.0}})
    (let [ops (:ops (arc-beam/effect-build-plan :mag-movement
                                                {:x 0.0 :y 65.0 :z 0.0}
                                                {:player-uuid "player-a" :x 0.3 :y 64.8 :z 0.2}
                                                0))
          alphas (into #{} (map (fn [op]
                                  (bit-and (bit-shift-right (long (:color op)) 24) 0xFF)))
                       ops)]
      (is (seq ops))
      (is (not (contains? alphas 0)) "no arc quad may be fully transparent")
      (is (= #{180 220 160} alphas)
          "outer/inner/line render at life-fade-alpha's flat full-brightness values"))))

(deftest two-owners-keep-mag-movement-state-independent-test
  (invoke-level-enqueue! "ctx-a" :mag-movement/fx-start {:mode :start :target {:x 1.0 :y 2.0 :z 3.0}})
  (invoke-level-enqueue! "ctx-b" :mag-movement/fx-start {:mode :start :target {:x 4.0 :y 5.0 :z 6.0}})
  (invoke-level-enqueue! "ctx-a" :mag-movement/fx-update {:mode :update :target {:x 7.0 :y 8.0 :z 9.0}})
  (let [snapshot (mag-movement-fx/fx-snapshot)]
    (is (= {:x 7.0 :y 8.0 :z 9.0}
           (:target (get (:effect-state snapshot) [:ctx "ctx-a"]))))
    (is (= {:x 4.0 :y 5.0 :z 6.0}
           (:target (get (:effect-state snapshot) [:ctx "ctx-b"]))))
    (mag-movement-fx/clear-fx-owner! [:ctx "ctx-a"])
    (let [after-clear (mag-movement-fx/fx-snapshot)]
      (is (nil? (get (:effect-state after-clear) [:ctx "ctx-a"])))
      (is (some? (get (:effect-state after-clear) [:ctx "ctx-b"]))))))

(deftest fx-snapshot-default-without-registered-state-test
  (is (= {:effect-state {}}
         (mag-movement-fx/fx-snapshot))))
