(ns cn.li.ac.content.ability.vecmanip.plasma-cannon-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            ;; arc-beam MUST precede the impl (AOT classes don't self-require)
            ;; so the [:plasma-cannon :level] defmethods are registered before
            ;; the stateful tests enqueue — otherwise effect-initial-state
            ;; falls through to the :default arc state.
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
            [cn.li.ac.ability.client.fx-templates.arc-beam.impl.plasma-cannon]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.tornado :as tornado]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.content.ability.vecmanip.plasma-cannon-fx :as pcfx]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(defn- reset-fixture [f]
  (try
        (level-effects/reset-level-effect-registry-for-test!)
        (pcfx/reset-fx-for-test!)
        (f)
        (finally
          (pcfx/reset-fx-for-test!)
          (level-effects/reset-level-effect-registry-for-test!))))

(use-fixtures :each reset-fixture)

(defn- event
  [ctx-id payload]
  {:payload payload
   :ctx-id ctx-id
   :channel :plasma-cannon/fx-update
   :owner-key [:ctx ctx-id]})

(deftest init-registers-plasma-cannon-fx-channels-test
  (let [registered-level* (atom nil)
        registered-topics* (atom #{})]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channel! (fn [topic _handler]
                                                      (swap! registered-topics* conj topic)
                                                      nil)]
      (pcfx/init!)
      (is (= :plasma-cannon (first @registered-level*)))
      (is (fn? (:enqueue-state-fn (second @registered-level*))))
      (is (= #{:plasma-cannon/fx-start
               :plasma-cannon/fx-update
               :plasma-cannon/fx-perform
               :plasma-cannon/fx-end}
             @registered-topics*)))))

(deftest fx-handler-routes-start-update-perform-end-payloads-test
  (let [handlers* (atom {})
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channel! (fn [topic handler]
                                                      (swap! handlers* assoc topic handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id ctx-id channel payload & opts]
                                                        (swap! enqueued* conj [effect-id ctx-id channel payload opts])
                                                        nil)]
      (pcfx/init!)
      ((get @handlers* :plasma-cannon/fx-start) "ctx-1" :plasma-cannon/fx-start {:charge-pos {:x 1.0 :y 64.0 :z 1.0}})
      ((get @handlers* :plasma-cannon/fx-update) "ctx-1" :plasma-cannon/fx-update {:charge-ticks 24
                                                                :fully-charged? true
                                                                :charge-pos {:x 1.0 :y 64.0 :z 1.0}
                                                                :flight-ticks 2
                                                                :state :go
                                                                :destination {:x 4.0 :y 64.0 :z 4.0}})
      ((get @handlers* :plasma-cannon/fx-perform) "ctx-1" :plasma-cannon/fx-perform {:pos {:x 2.0 :y 65.0 :z 2.0}})
      ((get @handlers* :plasma-cannon/fx-end) "ctx-1" :plasma-cannon/fx-end {:performed? true})
      (is (= [[:plasma-cannon "ctx-1" :plasma-cannon/fx-start
               ;; :tornado-base is the server-resolved ground point the charge
               ;; tornado stands on; absent here, the client falls back to 20
               ;; blocks below the charge position (the original's ray miss).
               {:mode :start :charge-pos {:x 1.0 :y 64.0 :z 1.0} :tornado-base nil}
               [:owner-key [:ctx "ctx-1"]]]
              [:plasma-cannon "ctx-1" :plasma-cannon/fx-update
               {:mode :update
                :charge-ticks 24
                :fully-charged? true
                :charge-pos {:x 1.0 :y 64.0 :z 1.0}
                :flight-ticks 2
                :state :go
                :destination {:x 4.0 :y 64.0 :z 4.0}}
               [:owner-key [:ctx "ctx-1"]]]
              [:plasma-cannon "ctx-1" :plasma-cannon/fx-perform
               {:mode :perform :pos {:x 2.0 :y 65.0 :z 2.0}}
               [:owner-key [:ctx "ctx-1"]]]
              [:plasma-cannon "ctx-1" :plasma-cannon/fx-end
               {:mode :end :performed? true}
               [:owner-key [:ctx "ctx-1"]]]]
             @enqueued*)))))

(deftest tick-build-plan-and-perform-effects-test
  (let [
        sound-calls* (atom [])
        particle-calls* (atom [])
        bridge-calls* (atom [])]
    (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "plasma-cannon-test"})
                  client-sounds/queue-sound-effect! (fn [& args]
                                                      (swap! sound-calls* conj args)
                                                      nil)
                  client-particles/queue-particle-effect! (fn [& args]
                                                            (swap! particle-calls* conj args)
                                                            nil)
                  client-bridge/run-client-effect! (fn [& args]
                                                     (swap! bridge-calls* conj args)
                                                     nil)
                  rand (fn [] 0.5)]
      (arc-beam/enqueue-for-test! :plasma-cannon "ctx-main" :plasma-cannon/fx-update {:mode :start :charge-pos {:x 1.0 :y 64.0 :z 1.0}})
      (arc-beam/enqueue-for-test! :plasma-cannon "ctx-main" :plasma-cannon/fx-update {:mode :update
                             :charge-ticks 24
                             :fully-charged? true
                             :charge-pos {:x 1.0 :y 64.0 :z 1.0}
                             :flight-ticks 2
                             :state :go
                             :destination {:x 4.0 :y 64.0 :z 4.0}})
      (dotimes [_ 10]
        (level-effects/update-effect-state! :plasma-cannon
          (fn [store] (arc-beam/effect-tick-state! :level :plasma-cannon store))))
      (let [plan (arc-beam/effect-build-plan :plasma-cannon nil nil 0)]
        ;; charge loop is a FollowEntitySound started once via the bridge —
        ;; only the fully-charged cue goes through the sound queue
        (is (= [[:mcmod/start-loop-sound-at-player
                 {:key "plasma-cannon/ctx-main" :sound-id "academy:vecmanip.plasma_cannon"
                  :owner-uuid "" :volume 0.5 :pitch 1.0}]]
               @bridge-calls*))
        (is (= 1 (count @sound-calls*)))
        (is (= 10 (count @particle-calls*)))
        (is (= 1 (count (filter #(= :plasma-body (:kind %)) (:ops plan)))))
        ;; The charge tornado is dead once the shot is in flight, but still
        ;; fading out (10 of its 30 dead ticks elapsed), so it still renders.
        (is (seq (filter #(= tornado/ring-texture (:texture %)) (:ops plan))))
        (is (= 10 (get-in (pcfx/fx-snapshot)
                          [:effect-state [:ctx "ctx-main"] :ticks]))))
      (reset! sound-calls* [])
      (reset! particle-calls* [])
      (reset! bridge-calls* [])
      (arc-beam/enqueue-for-test! :plasma-cannon "ctx-main" :plasma-cannon/fx-update {:mode :perform :pos {:x 2.0 :y 65.0 :z 2.0}})
      (is (= 1 (count @sound-calls*)))
      (is (= 13 (count @particle-calls*))))))

(deftest charge-tornado-renders-while-charging-test
  ;; The original spawns a Tornado entity in c_begin and only kills it when the
  ;; shot goes (STATE_GO); the port rendered nothing at all during the charge.
  (with-redefs [client-particles/current-effect-owner (fn [] {:client-session-id "plasma-cannon-test"})
                client-sounds/queue-sound-effect! (fn [& _] nil)
                client-particles/queue-particle-effect! (fn [& _] nil)
                client-bridge/run-client-effect! (fn [& _] nil)]
    (arc-beam/enqueue-for-test! :plasma-cannon "ctx-main" :plasma-cannon/fx-start
      {:mode :start
       :charge-pos {:x 1.0 :y 79.0 :z 1.0}
       :tornado-base {:x 1.0 :y 64.0 :z 1.0}})
    (arc-beam/enqueue-for-test! :plasma-cannon "ctx-main" :plasma-cannon/fx-update
      {:mode :update :state :charging :charge-ticks 5
       :charge-pos {:x 1.0 :y 79.0 :z 1.0}})
    (dotimes [_ 10]
      (level-effects/update-effect-state! :plasma-cannon
        (fn [store] (arc-beam/effect-tick-state! :level :plasma-cannon store))))
    (let [rings (filter #(= tornado/ring-texture (:texture %))
                        (:ops (arc-beam/effect-build-plan :plasma-cannon nil nil 0)))]
      (is (seq rings) "tornado ring quads are part of the charge plan")
      ;; Every ring segment covers 1/20 of the texture (TornadoRenderer div).
      (is (= #{50000} (into #{} (map (fn [{:keys [u0 u1]}] (Math/round (* 1.0e6 (- u1 u0))))) rings)))
      ;; Tornado.alpha at tick 10 = 10/20, halved in onUpdate, then *0.7 at
      ;; render time.
      (is (= #{(int (* 255.0 0.5 0.5 0.7))} (into #{} (map (comp :a :color)) rings)))
      ;; The column stands on the ground point from the start payload, not at
      ;; the plasma body 15 blocks up.
      (is (every? (fn [op]
                    (every? (fn [^cn.li.mcmod.math.V3 p] (< (.-y p) 78.0))
                            [(:p0 op) (:p1 op) (:p2 op) (:p3 op)]))
                  rings)))))
