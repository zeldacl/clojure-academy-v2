(ns cn.li.ac.content.ability.electromaster.railgun-fx-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
            [cn.li.ac.ability.client.effects.arc-fx]
            [cn.li.ac.ability.client.effects.rv3]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.ac.content.ability.electromaster.railgun-fx :as railgun-fx]))

(defn- reset-fixture [f]
  (try
        (level-effects/reset-level-effect-registry-for-test!)
        (railgun-fx/reset-fx-for-test!)
        (railgun-fx/reset-charge-glows-for-test!)
        (f)
        (finally
          (railgun-fx/reset-fx-for-test!)
          (railgun-fx/reset-charge-glows-for-test!)
          (level-effects/reset-level-effect-registry-for-test!))))

(use-fixtures :each reset-fixture)

(defn- event [ctx-id channel payload]
  {:payload payload
   :ctx-id ctx-id
   :channel channel
   :owner-key [:ctx ctx-id]})

(deftest init-registers-owner-aware-railgun-fx-test
  (let [registered-level* (atom nil)
        registered-topics* (atom #{})]
    (with-redefs [level-effects/register-level-effect! (fn [effect-id effect-map]
                                                         (reset! registered-level* [effect-id effect-map])
                                                         nil)
                  fx-registry/register-fx-channel! (fn [topic _handler]
                                                      (swap! registered-topics* conj topic)
                                                      nil)]
      (railgun-fx/init!)
      (is (= :railgun-shot (first @registered-level*)))
      (is (fn? (:enqueue-state-fn (second @registered-level*))))
      (is (= #{:railgun/fx-shot :railgun/fx-reflect
               :railgun/fx-charge-start :railgun/fx-charge-update :railgun/fx-charge-end}
             @registered-topics*)))))

(deftest fx-handler-routes-with-ctx-metadata-test
  (let [handlers* (atom {})
        enqueued* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channel! (fn [topic handler]
                                                      (swap! handlers* assoc topic handler)
                                                      nil)
                  level-effects/enqueue-level-effect! (fn [effect-id ctx-id channel payload & opts]
                                                        (swap! enqueued* conj [effect-id ctx-id channel payload opts])
                                                        nil)]
      (railgun-fx/init!)
      ((get @handlers* :railgun/fx-shot) "ctx-rail" :railgun/fx-shot {:mode :block-hit
                                               :start {:x 0.0 :y 64.0 :z 0.0}
                                               :end {:x 8.0 :y 64.0 :z 0.0}
                                               :world-id "minecraft:overworld"})
      (is (= [[:railgun-shot
               "ctx-rail"
               :railgun/fx-shot
               {:mode :block-hit
                :start {:x 0.0 :y 64.0 :z 0.0}
                :end {:x 8.0 :y 64.0 :z 0.0}
                :world-id "minecraft:overworld"}
               '(:owner-key [:ctx "ctx-rail"])]]
             @enqueued*)))))

(deftest enqueue-perform-adds-beam-and-builds-plan-test
  (do
    (arc-beam/enqueue-for-test! :railgun-shot "ctx-main" :railgun/fx-shot {:start {:x 0.0 :y 64.0 :z 0.0}
                                           :end {:x 3.0 :y 64.0 :z 3.0}
                                           :hit-distance 18.0})
    ;; Upstream beam length blends from zero over its first 150 ms.
    (level-effects/update-effect-state! :railgun-shot
      (fn [store] (arc-beam/effect-tick-state! :level :railgun-shot store)))
    (let [plan (arc-beam/effect-build-plan :railgun-shot {:x 0.0 :y 65.0 :z 0.0} nil 0)]
      (is (some? plan))
      (is (seq (:ops plan)))
      (is (= 13 (count (filter #(= :line (:kind %)) (:ops plan))))
          "enhanced center highlight plus 12-segment endpoint ring remain layered on the beam"))
    (is (= 1 (count (get (:beam-effects (railgun-fx/fx-snapshot)) [:ctx "ctx-main"]))))))

(deftest two-owners-keep-railgun-beams-independent-test
  (do
    (arc-beam/enqueue-for-test! :railgun-shot "ctx-a" :railgun/fx-shot {:start {:x 0.0 :y 64.0 :z 0.0}
                                         :end {:x 6.0 :y 64.0 :z 0.0}})
    (arc-beam/enqueue-for-test! :railgun-shot "ctx-b" :railgun/fx-reflect {:start {:x 0.0 :y 65.0 :z 0.0}
                                           :end {:x 6.0 :y 65.0 :z 0.0}})
    (let [snapshot (railgun-fx/fx-snapshot)]
      (is (= 1 (count (get (:beam-effects snapshot) [:ctx "ctx-a"]))))
      (is (= 1 (count (get (:beam-effects snapshot) [:ctx "ctx-b"]))))
      (railgun-fx/clear-fx-owner! [:ctx "ctx-a"])
      (let [after-clear (railgun-fx/fx-snapshot)]
        ;; Clearing an owner leaves its live beams alone — they are one-shot
        ;; world visuals that expire on their own ttl (see the regression test
        ;; below) — and never touches another owner's.
        (is (= 1 (count (get (:beam-effects after-clear) [:ctx "ctx-a"]))))
        (is (= 1 (count (get (:beam-effects after-clear) [:ctx "ctx-b"]))))))))

(deftest fired-beam-outlives-its-context-test
  ;; Railgun's context ends on the same tick the shot goes out (the charge
  ;; window closes), and client_ui_hooks' MSG-CTX-TERMINATED handler calls
  ;; level-effects/clear-effect-owner!. Upstream's EntityRailgunFX is a world
  ;; entity with its own ~2.5 s life that the ability context never kills, so
  ;; the beam has to survive that: clearing the owner used to delete it a tick
  ;; or two after firing, and the shot rendered nothing at all.
  (arc-beam/enqueue-for-test! :railgun-shot "ctx-fire" :railgun/fx-shot
    {:start {:x 0.0 :y 64.0 :z 0.0}
     :end {:x 0.0 :y 64.0 :z 30.0}
     :hit-distance 30.0})
  (railgun-fx/clear-fx-owner! [:ctx "ctx-fire"])
  (is (= 1 (count (get (:beam-effects (railgun-fx/fx-snapshot)) [:ctx "ctx-fire"])))
      "a fired beam survives its context ending")
  (is (seq (:ops (arc-beam/effect-build-plan :railgun-shot {:x 0.0 :y 65.0 :z 0.0} nil 0)))
      "and still renders")
  ;; It is the ttl, not the context, that ends it.
  (dotimes [_ 51]
    (level-effects/update-effect-state! :railgun-shot
      (fn [store] (arc-beam/effect-tick-state! :level :railgun-shot store))))
  (is (empty? (get (:beam-effects (railgun-fx/fx-snapshot)) [:ctx "ctx-fire"]))
      "and expires on its own ttl"))

(deftest clear-owner-still-stops-the-charge-marker-test
  ;; The charge marker is context-bound: it exists to keep the effect non-idle
  ;; while charging, so an externally aborted context must still stop it.
  (arc-beam/enqueue-for-test! :railgun-shot "ctx-charge" :railgun/fx-charge-start
    {:mode :charge-start :source-player-id "player-a"})
  (is (some? (get (:charging (railgun-fx/fx-snapshot)) [:ctx "ctx-charge"])))
  (railgun-fx/clear-fx-owner! [:ctx "ctx-charge"])
  (is (nil? (get (:charging (railgun-fx/fx-snapshot)) [:ctx "ctx-charge"]))))

(deftest fx-snapshot-default-without-registered-state-test
  (is (= {:beam-effects {} :charging {}}
         (railgun-fx/fx-snapshot))))

(deftest charging-lifecycle-keeps-one-shot-animation-after-charge-end-test
  ;; :charging is a pure idle-gating marker (see impl/railgun_shot.clj):
  ;; before this fix, no level-effect state existed during the charge-only
  ;; window (no beam yet), so level-effects' idle-gating suppressed build-plan
  ;; entirely and the charge-hand animation never rendered until a beam
  ;; existed. default-empty-state? only treats :railgun-shot as idle once
  ;; BOTH :beam-effects and :charging are empty.
  (do
    (is (empty? (:charging (railgun-fx/fx-snapshot))))
    (arc-beam/enqueue-for-test! :railgun-shot "ctx-charge" :railgun/fx-charge-start {:mode :charge-start})
    (is (contains? (:charging (railgun-fx/fx-snapshot)) [:ctx "ctx-charge"]))

    (arc-beam/enqueue-for-test! :railgun-shot "ctx-charge" :railgun/fx-charge-update {:mode :charge-update :charge-ticks-left 5})
    (is (contains? (:charging (railgun-fx/fx-snapshot)) [:ctx "ctx-charge"]))

    (arc-beam/enqueue-for-test! :railgun-shot "ctx-charge" :railgun/fx-charge-end {:mode :charge-end})
    (is (contains? (:charging (railgun-fx/fx-snapshot)) [:ctx "ctx-charge"])
        "upstream RailgunHandEffect continues its full 1.6-second animation")))

(deftest charge-hand-visual-renders-once-charging-marker-is-live-test
  ;; RailgunHandEffect has two branches and upstream picks exactly one: the
  ;; scaled hand quad in first person, the 2x2 billboard on the player model
  ;; otherwise. The railgun_charge entity is this port's second branch, and it
  ;; is spawned on every client including the caster's — so the hand quad has
  ;; to stay out of third person or the caster plays both at once.
  (let [hand {:x 1.0 :y 65.0 :z 1.0 :player-uuid "p1" :first-person? true
              :player-yaw-rad 0.0 :player-pitch-rad 0.0}]
    (arc-beam/enqueue-for-test!
      :railgun-shot "ctx-charge" :railgun/fx-charge-start
      {:mode :charge-start :source-player-id "p1"})
    (let [plan (arc-beam/effect-build-plan
                 :railgun-shot {:x 0.0 :y 65.0 :z 0.0} hand 0)]
      (is (seq (:ops plan)))
      (is (every? #(= :quad (:kind %)) (:ops plan))
          "no beam exists yet — the only ops should be the charge-hand quad"))
    (let [plan (arc-beam/effect-build-plan
                 :railgun-shot {:x 0.0 :y 65.0 :z 0.0}
                 (assoc hand :first-person? false) 0)]
      (is (nil? (:ops plan))
          "in third person the entity-anchored billboard is the only animation"))))

(deftest charge-start-spawns-glow-anchored-to-caster-test
  (let [handlers* (atom {})
        run-calls* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channel! (fn [topic handler]
                                                      (swap! handlers* assoc topic handler)
                                                      nil)
                  client-bridge/run-client-effect! (fn [op payload]
                                                      (swap! run-calls* conj [op payload])
                                                      (when (= op :mcmod/spawn-scripted-effect-at-player)
                                                        "entity-uuid-1"))]
      (railgun-fx/init!)
      ((get @handlers* :railgun/fx-charge-start) "ctx-glow" :railgun/fx-charge-start
       {:mode :charge-start :source-player-id "caster-uuid"})
      (is (= [[:mcmod/spawn-scripted-effect-at-player
               {:effect-id "railgun_charge" :owner-uuid "caster-uuid"}]]
             @run-calls*)
          "spawns anchored to the CASTER's uuid from the payload, not the local viewer"))))

(deftest charge-start-without-source-player-id-does-not-spawn-test
  (let [handlers* (atom {})
        run-calls* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channel! (fn [topic handler]
                                                      (swap! handlers* assoc topic handler)
                                                      nil)
                  client-bridge/run-client-effect! (fn [op payload]
                                                      (swap! run-calls* conj [op payload])
                                                      nil)]
      (railgun-fx/init!)
      ((get @handlers* :railgun/fx-charge-start) "ctx-glow" :railgun/fx-charge-start
       {:mode :charge-start})
      (is (empty? @run-calls*)))))

(deftest charge-end-clears-enhanced-world-glow-without-affecting-hand-animation-test
  (let [handlers* (atom {})
        run-calls* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channel! (fn [topic handler]
                                                      (swap! handlers* assoc topic handler)
                                                      nil)
                  client-bridge/run-client-effect! (fn [op payload]
                                                      (swap! run-calls* conj [op payload])
                                                      (when (= op :mcmod/spawn-scripted-effect-at-player)
                                                        "entity-uuid-2"))]
      (railgun-fx/init!)
      ((get @handlers* :railgun/fx-charge-start) "ctx-glow" :railgun/fx-charge-start
       {:mode :charge-start :source-player-id "caster-uuid"})
      ((get @handlers* :railgun/fx-charge-end) "ctx-glow" :railgun/fx-charge-end
       {:mode :charge-end :source-player-id "caster-uuid"})
      (is (= [:mcmod/spawn-scripted-effect-at-player
              :mcmod/remove-local-scripted-effect]
             (mapv first @run-calls*)))
      (is (= [:mcmod/remove-local-scripted-effect
              {:entity-uuid "entity-uuid-2"}]
             (last @run-calls*))))))

(deftest charge-end-without-a-prior-start-is-a-no-op-test
  ;; Matches railgun.clj's abort handler comment: fx-charge-end fires
  ;; unconditionally on abort even when no charge was ever active.
  (let [handlers* (atom {})
        run-calls* (atom [])]
    (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                  fx-registry/register-fx-channel! (fn [topic handler]
                                                      (swap! handlers* assoc topic handler)
                                                      nil)
                  client-bridge/run-client-effect! (fn [op payload]
                                                      (swap! run-calls* conj [op payload])
                                                      nil)]
      (railgun-fx/init!)
      ((get @handlers* :railgun/fx-charge-end) "ctx-never-started" :railgun/fx-charge-end
       {:mode :charge-end :source-player-id "caster-uuid"})
      (is (empty? @run-calls*)))))

(deftest charging-marker-expires-via-ttl-without-explicit-end-test
  (do
    (arc-beam/enqueue-for-test! :railgun-shot "ctx-stale" :railgun/fx-charge-start {:mode :charge-start})
    (is (contains? (:charging (railgun-fx/fx-snapshot)) [:ctx "ctx-stale"]))
    (dotimes [_ 32]
      (level-effects/update-effect-state! :railgun-shot
        (fn [store] (arc-beam/effect-tick-state! :level :railgun-shot store))))
    (is (not (contains? (:charging (railgun-fx/fx-snapshot)) [:ctx "ctx-stale"])))))

;; ---------------------------------------------------------------------------
;; EntityRailgunFX geometry + SubArc lifecycle parity
;; ---------------------------------------------------------------------------

(defn- fire-beam! [ctx-id length]
  (arc-beam/enqueue-for-test! :railgun-shot ctx-id :railgun/fx-shot
    {:mode :perform
     :start {:x 0.0 :y 64.0 :z 0.0}
     :end {:x (double length) :y 64.0 :z 0.0}
     :hit-distance (double length)}))

(defn- tick-fx! [n]
  (dotimes [_ n]
    (level-effects/update-effect-state! :railgun-shot
      (fn [store] (arc-beam/effect-tick-state! :level :railgun-shot store)))))

(defn- beam-state [ctx-id]
  (first (get (:beam-effects (railgun-fx/fx-snapshot)) [:ctx ctx-id])))

(defn- quad-extent
  "Largest distance between any two quad corners in the op list."
  [ops]
  (let [pts (mapcat (fn [op] (keep op [:p0 :p1 :p2 :p3])) ops)]
    (if (< (count pts) 2)
      0.0
      (apply max
             (for [^cn.li.mcmod.math.V3 a pts
                   ^cn.li.mcmod.math.V3 b pts]
               (let [dx (- (.-x a) (.-x b))
                     dy (- (.-y a) (.-y b))
                     dz (- (.-z a) (.-z b))]
                 (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))))))))

(deftest sub-arcs-are-drawn-at-upstream-scale-test
  ;; SubArcHandler.drawAll scales every template by 0.3 and centres it on its
  ;; placement point. Without the scale a 2-3 unit template covered 2-3 blocks
  ;; instead of 0.6-0.9, more than three times the original.
  (fire-beam! "ctx-arc" 20.0)
  ;; sub-arcs start hidden and flicker on, so give them a few ticks
  (tick-fx! 6)
  (let [beam (beam-state "ctx-arc")
        placement (first (filter :draw? (:arc-placements beam)))]
    (when placement
      (let [ops (cn.li.ac.ability.client.effects.arc-fx/railgun-arc-ops
                  (cn.li.ac.ability.client.effects.rv3/v3 0.0 70.0 0.0)
                  (assoc beam :arc-placements [placement])
                  {})
            centre (cn.li.ac.ability.client.effects.rv3/v3
                     (double (:distance placement)) 64.0 0.0)
            extent (quad-extent ops)
            offsets (map (fn [op]
                           (let [^cn.li.mcmod.math.V3 p (:p0 op)]
                             (Math/sqrt (+ (Math/pow (- (.-x p) (.-x centre)) 2)
                                           (Math/pow (- (.-y p) (.-y centre)) 2)
                                           (Math/pow (- (.-z p) (.-z centre)) 2)))))
                         ops)]
        (is (seq ops))
        (is (< extent 1.2)
            (str "a 2-3 unit template at 0.3 scale spans under a block, was " extent))
        (is (< (apply max offsets) 0.8)
            "and it straddles its placement point instead of trailing off it")))))

(deftest sub-arcs-flicker-and-expire-like-upstream-test
  (fire-beam! "ctx-life" 20.0)
  (let [initial (:arc-placements (beam-state "ctx-life"))]
    (is (seq initial))
    (is (every? (comp false? :draw?) initial)
        "SubArc.draw starts false — an arc flickers on before it is ever seen")
    (is (every? #(and (<= 0 (:tex-id %)) (< (:tex-id %) 15)) initial))
    ;; over 30 ticks the visibility toggles rather than staying constant
    (let [seen (atom #{})
          tex-ids (atom #{})]
      (dotimes [_ 25]
        (tick-fx! 1)
        (let [arc (first (:arc-placements (beam-state "ctx-life")))]
          (swap! seen conj (boolean (:draw? arc)))
          (swap! tex-ids conj (:tex-id arc))))
      (is (= #{true false} @seen) "a single arc blinks on and off")
      (is (> (count @tex-ids) 1) "and cycles through templates as it goes"))
    ;; EntityRailgunFX.onUpdate clears the whole handler at age 30
    (tick-fx! 10)
    (is (empty? (:arc-placements (beam-state "ctx-life")))
        "and all of them are gone for the beam's last 20 ticks")
    (is (some? (beam-state "ctx-life")) "while the beam itself is still alive")))

(deftest beam-bore-matches-the-original-cylinders-test
  ;; cylinderOut radius 0.13, cylinderIn 0.09, glow width 1.1 (halved by
  ;; drawBoard). The port had 0.45/0.28/1.5 — three times the original.
  (fire-beam! "ctx-bore" 10.0)
  (tick-fx! 1)
  (let [plan (arc-beam/effect-build-plan
               :railgun-shot {:x 0.0 :y 70.0 :z 0.0} nil 0)
        ;; widest quad across the beam axis (the beam runs along +x at y=64)
        spread (fn [ops]
                 (apply max 0.0
                        (for [op ops
                              :let [^cn.li.mcmod.math.V3 a (:p0 op)
                                    ^cn.li.mcmod.math.V3 b (:p1 op)]
                              :when (and a b)]
                          (Math/abs (- (.-y a) (.-y b))))))]
    (is (seq (:ops plan)))
    ;; the glow board is the widest piece at 2 * 0.55
    (is (< (spread (:ops plan)) 1.2)
        "nothing in the beam is wider than upstream's 1.1 glow board")))
