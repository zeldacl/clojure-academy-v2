(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.current-charging
  (:require [cn.li.ac.ability.client.arc-patterns :as arc-patterns]
            [cn.li.ac.ability.client.effects.rv3 :as rv3]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.util.log :as log]
            [clojure.string :as str]))

(defn- visual-max-ticks
  "Read the visual-max-ticks for current-charging from skill config.
   Falls back to 40 ticks (2 seconds at 20 tps)."
  []
  (max 1 (int (or (skill-config/tunable-int :current-charging :charge.visual-max-ticks) 40))))


(def ^:private default-state
  {:active? false
   :blending? false
   :is-item false
   :good? false
   :charge-ticks 0
   :charge-ratio 0.0
   :target nil
  :caster-pos nil
   :block-pos nil
   :charged 0.0
   :started-at-ms 0
  :ending-at-ms 0
  :updated-at-ms 0})

(def ^:private blend-out-ms 200)
(def ^:private active-stale-ms 500)









(defn- current-store []
  (let [store (cn.li.ac.ability.client.fx-templates.arc-beam/snapshot :current-charging)]
    (if (contains? store :states)
      store
      {:states {}})))

(defn- state-for-selector [store selector]
  (let [states (:states store)]
    (or (cond
          (vector? selector)
          (get states selector)

          (some? selector)
          (some (fn [[_ st]]
                  (when (and (:source-player-id st)
                             (= (str selector) (str (:source-player-id st))))
                    st))
                states)

          :else
          (or (some (fn [[_ st]]
                      (when (:active? st) st))
                    states)
              (some (fn [[_ st]]
                      (when (:blending? st) st))
                    states)))
        default-state)))



(defn- now-ms []
  ;; Use game-time so charge animations pause with the game.
  (client-bridge/game-time-ms))

(defn- normalize-ratio [charge-ticks]
  (let [ticks (max 0 (long (or charge-ticks 0)))
        ratio (/ (double ticks) (double (visual-max-ticks)))]
    (max 0.0 (min 1.0 ratio))))

(defn- resolve-owner-key [ctx-id _channel explicit-owner-key payload]
  (or explicit-owner-key
      (when-let [source-player-id (:source-player-id payload)]
        [:source-player source-player-id])
      [:ctx ctx-id]))

(def ^:private charge-loop-sound (modid/namespaced-path "em.charge_loop"))

(defn- base-meta [owner-key ctx-id channel payload]
  {:owner-key owner-key
   :ctx-id ctx-id
   :channel channel
   :source-player-id (:source-player-id payload)
   :world-id (:world-id payload)})

;; Runs synchronously inside the :current-charging fx-spec's dispatch
;; doseq (fx_spec.clj channel-handler), which processes :targets in
;; declared order ([:hand :level] here) with no per-target exception
;; isolation — an uncaught throw here would abort that doseq before the
;; :level track (the one build-plan reads for the beam/ring) ever gets its
;; state update for this tick. Client-effect side calls must never be
;; allowed to break the render-state pipeline, so every path is guarded.

(defn- start-loop-sound-at! [owner-key pos]
  (try
    (let [{:keys [x y z]} (or pos {:x 0.0 :y 0.0 :z 0.0})]
      (client-bridge/run-client-effect! :mcmod/start-loop-sound
        {:key (str owner-key)
         :sound-id charge-loop-sound
         :volume 0.8
         :pitch 1.0
         :x (double (or x 0.0))
         :y (double (or y 0.0))
         :z (double (or z 0.0))}))
    (catch Throwable e
      (log/warn "current-charging: start-loop-sound-at! failed" e))))

(defn- update-loop-sound-position! [owner-key pos]
  (when pos
    (try
      (let [{:keys [x y z]} pos]
        (client-bridge/run-client-effect! :mcmod/update-loop-sound-position
          {:key (str owner-key)
           :x (double (or x 0.0))
           :y (double (or y 0.0))
           :z (double (or z 0.0))}))
      (catch Throwable e
        (log/warn "current-charging: update-loop-sound-position! failed" e)))))

(defn- stop-loop-sound! [owner-key]
  (try
    (client-bridge/run-client-effect! :mcmod/stop-loop-sound {:key (str owner-key)})
    (catch Throwable e
      (log/warn "current-charging: stop-loop-sound! failed" e))))

(defn- enqueue-state! [store ctx-id channel owner-key payload]
  (let [store* (if (contains? (or store {}) :states)
                 (or store {:states {}})
                 {:states {}})
        {:keys [mode] :as payload*} (or payload {})
        owner-key* (resolve-owner-key ctx-id channel owner-key payload*)]
    (case mode
      :start
      (let [ts (now-ms)
            meta (base-meta owner-key* ctx-id channel payload*)]
        (start-loop-sound-at! owner-key* (:caster-pos payload*))
        (assoc-in store* [:states owner-key*]
                  (merge default-state
                         meta
                         {:active? true
                          :blending? false
                          :is-item (boolean (:is-item payload*))
                          :good? false
                          :charge-ticks 0
                          :charge-ratio 0.0
                          :target nil
                          :caster-pos (:caster-pos payload*)
                          :block-pos nil
                          :charged 0.0
                          :started-at-ms ts
                          :ending-at-ms 0
                          :updated-at-ms ts})))

      :update
      (do
        (update-loop-sound-position! owner-key* (:caster-pos payload*))
        (let [ts (now-ms)]
          (update-in store* [:states owner-key*]
                     (fn [state]
                       (-> (merge default-state state (base-meta owner-key* ctx-id channel payload*))
                           (merge {:active? true
                                   :blending? false
                                   :updated-at-ms ts})
                           (cond-> (contains? payload* :is-item)
                             (assoc :is-item (boolean (:is-item payload*))))
                           (cond-> (contains? payload* :good?)
                             (assoc :good? (boolean (:good? payload*))))
                           (cond-> (contains? payload* :charge-ticks)
                             (assoc :charge-ticks (max 0 (long (:charge-ticks payload*)))
                                    :charge-ratio (normalize-ratio (:charge-ticks payload*))))
                           (cond-> (contains? payload* :target)
                             (assoc :target (:target payload*)))
                           (cond-> (contains? payload* :caster-pos)
                             (assoc :caster-pos (:caster-pos payload*)))
                           (cond-> (contains? payload* :block-pos)
                             (assoc :block-pos (:block-pos payload*)))
                           (cond-> (contains? payload* :charged)
                             (assoc :charged (double (:charged payload*)))))))))

      :end
      (let [ts (now-ms)]
        ;; Sound is stopped by tick-state! once the blend window elapses,
        ;; not here — this lets the loop linger through the same short
        ;; fade the visual rings use instead of cutting off on key-up.
        (update-in store* [:states owner-key*]
             (fn [state]
               (-> (merge default-state state (base-meta owner-key* ctx-id channel payload*))
             (merge {:active? false
               :blending? true
               :is-item (boolean (:is-item payload*))
               :charge-ticks 0
               :charge-ratio 0.0
               :ending-at-ms ts
               :updated-at-ms ts})
             (assoc :good? false)))))

      store*)))

(defn- tick-state!
  [store]
  (let [store* (if (contains? (or store {}) :states)
                 (or store {:states {}})
                 {:states {}})
        now-ms (now-ms)
        states' (into {}
                      (keep (fn [[owner-key st]]
                              (cond
                                (and (:active? st)
                                     (< (- now-ms (long (or (:updated-at-ms st)
                                                             (:started-at-ms st)
                                                             0)))
                                        active-stale-ms))
                                [owner-key st]

                                (and (:blending? st)
                                     (< (- now-ms (long (or (:ending-at-ms st) 0))) blend-out-ms))
                                [owner-key st]

                                :else
                                (do
                                  (stop-loop-sound! owner-key)
                                  nil))))
                      (:states store*))]
    (assoc store* :states states')))

;; A perfectly straight billboard beam is geometrically invisible whenever
;; the camera looks straight down its own axis — exactly this skill's
;; scenario, since the beam always runs from the caster's own eye to their
;; own crosshair target, so camera direction ≈ beam direction every frame.
;; A flat quad's width only reads as a face when it's angled toward the
;; camera; end-on, its silhouette has zero screen-space width regardless of
;; world-space width, color, or texture. Original AcademyCraft's own
;; EntityArc/ArcPatterns never draws a smooth straight beam for exactly this
;; reason — it's always a jagged zigzag path (arc-patterns/generate-zigzag-
;; segments), whose per-segment sideways deviation guarantees some part of
;; the arc is never edge-on, from any viewing angle including straight down
;; the caster's own sightline. :charging is the original's own named preset
;; for this skill ("chargingArc").
(defn- zigzag-ops
  [cam-v start end pattern seed]
  (let [vertices (arc-patterns/generate-zigzag-segments start end
                   {:segments (:segments pattern)
                    :amplitude (:amplitude pattern)
                    :seed seed})
        life-ratio 0.5] ;; life-fade-alpha's flat full-brightness middle 60%
    (ru/zigzag-arc-ops cam-v vertices pattern
      {:life-ratio life-ratio
       :wiggle-phase (arc-patterns/wiggle-phase)
       :effective-wiggle (arc-patterns/effective-wiggle-amount pattern life-ratio)})))

;; :charging's own amplitude (0.3) is calibrated for the original's long-
;; range arcs — proportional to length, so it's still a fraction of THIS
;; beam's length, but at current-charging's typical close range (a few
;; blocks) that fraction swings wide enough to read as scattered spokes
;; converging on the target rather than one coherent bolt from the caster.
;; :fork-count 2 also spawns random side-branches near the target, which at
;; close range can itself look like several arcs converging from different
;; angles — disabled for now (TEMP: testing whether that's the "shooting
;; from all directions" report, not the zigzag itself) so the beam reads as
;; one continuous bolt.
(def ^:private charging-beam-pattern
  (assoc (arc-patterns/get-pattern :charging) :amplitude 0.1 :fork-count 0))

;; Matches original's EntitySurroundArc, which is built from small arc-
;; textured strands, not a plain circle outline. Non-zero amplitude: a flat
;; ring strand can land edge-on to the camera the same way the main beam
;; did — worse, the ring's whole plane is horizontal, so when the player
;; looks roughly level at the target (the common case), most strands are
;; near-edge-on simultaneously, not just one or two. Needs enough
;; amplitude/segments to reliably break that alignment despite each strand
;; being short.
(def ^:private ring-arc-pattern
  (assoc (arc-patterns/get-pattern :thin-continuous)
         :width 0.05
         :amplitude 0.35
         :segments 4
         :fork-count 0
         :color-outer {:r 150 :g 232 :b 255}
         :color-inner {:r 235 :g 252 :b 255}
         :color-line {:r 190 :g 244 :b 255}))

(defn- own-state?
  [st hand-center-pos]
  (or (nil? (:source-player-id st))
      (nil? (:player-uuid hand-center-pos))
      (= (str (:source-player-id st)) (str (:player-uuid hand-center-pos)))))

(defn- ring-arc-ops
  "Small arc-textured strands orbiting [cx,y,cz] at the given radius —
  matches original's EntitySurroundArc look far better than a smooth line
  circle: fewer, distinct segments with slight per-segment radius jitter so
  they read as separate crackling arcs rather than a ring outline."
  [cam-v cx y cz radius ticks segments pattern]
  (vec
    (mapcat
      (fn [idx]
        (let [a0 (/ (* 2.0 Math/PI idx) segments)
              a1 (/ (* 2.0 Math/PI (inc idx)) segments)
              jitter (* 0.06 (Math/sin (+ (* 0.31 (double ticks)) (* idx 1.7))))
              r0 (+ radius jitter)
              r1 (+ radius (- jitter))
              p0 (rv3/v3 (+ cx (* r0 (Math/cos a0))) y (+ cz (* r0 (Math/sin a0))))
              p1 (rv3/v3 (+ cx (* r1 (Math/cos a1))) y (+ cz (* r1 (Math/sin a1))))]
          ;; zigzag-ops returns a vector of several op-maps per call (quads +
          ;; line) — a bare `for` here would collect one such vector per
          ;; segment instead of flattening them, silently dropping every
          ;; ring op at render time (:kind lookup on a vector, not a map,
          ;; returns nil and falls through sort-ops' case). mapcat flattens.
          (zigzag-ops cam-v p0 p1 pattern (+ (* 1000 idx) (long ticks)))))
      (range segments))))

(defn- target-ring-ops
  [cam-v target ticks charge-ratio]
  (let [base-radius (+ 0.45 (* 0.25 (double charge-ratio)))
        pulse (+ base-radius (* 0.07 (Math/sin (* 0.24 (double ticks)))))
        tx (double (:x target))
        y (+ (double (:y target)) 0.05)
        tz (double (:z target))]
    (ring-arc-ops cam-v tx y tz pulse ticks 10 ring-arc-pattern)))

(defn- caster-ring-ops
  [cam-v caster-pos ticks]
  (let [radius 0.45
        pulse (+ radius (* 0.08 (Math/sin (* 0.22 (double ticks)))))
        cx (double (:x caster-pos))
        y (+ (double (:y caster-pos)) 0.9)
        cz (double (:z caster-pos))]
    (ring-arc-ops cam-v cx y cz pulse ticks 8 ring-arc-pattern)))

(defn- build-plan
  [camera-pos hand-center-pos tick]
  (let [store (:states (level-effects/effect-state-snapshot :current-charging))
        active-states (filter :active? (vals (or store {})))
        cam-v (rv3/map->v3 camera-pos)
        trace? (zero? (mod (long (or tick 0)) 20))
        beam-count* (atom 0)
        ring-count* (atom 0)
        ops (vec
              (mapcat
                (fn [st]
                  (let [own? (own-state? st hand-center-pos)
                        ;; Item-mode ring anchor: the local player's rendered
                        ;; hand offset when it's our own effect (frame-smooth),
                        ;; else the last synced caster-pos for bystanders.
                        hand-pos (or (and own? hand-center-pos)
                                     (:caster-pos st)
                                     hand-center-pos)
                        ;; Beam origin must be the pure eye position, matching
                        ;; what the server's raycast actually aimed from
                        ;; (current_charging.clj's player-view) — using the
                        ;; hand-offset anchor here instead visibly skews the
                        ;; beam away from the crosshair/target.
                        caster-pos (:caster-pos st)
                        target (:target st)
                        ticks (long (or (:charge-ticks st) 0))
                        ratio (double (or (:charge-ratio st) 0.0))
                        item? (boolean (:is-item st))
                        good? (boolean (:good? st))
                        beam-ops (when (and (not item?) (map? caster-pos) (map? target))
                                   (zigzag-ops cam-v
                                               (rv3/map->v3 caster-pos)
                                               (rv3/map->v3 target)
                                               charging-beam-pattern
                                               ticks))
                        ;; Matches original: the surround ring only ever
                        ;; appears around the TARGET block once it's a valid
                        ;; energy receiver (block mode) — never around the
                        ;; caster in block mode.
                        ring-ops (when (and (not item?) good? (map? target))
                                   (target-ring-ops cam-v target ticks ratio))
                        ;; Item mode has no beam/target at all — original
                        ;; wraps the surround ring around the PLAYER instead
                        ;; (new EntitySurroundArc(player)).
                        item-ring-ops (when (and item? (map? hand-pos))
                                        (caster-ring-ops cam-v (dissoc hand-pos :player-uuid) ticks))]
                    (when trace?
                      (swap! beam-count* + (count beam-ops))
                      (swap! ring-count* + (count ring-ops) (count item-ring-ops)))
                    (concat beam-ops ring-ops item-ring-ops)))
                active-states))]
    (when trace?
      (log/info "[CC-TRACE][CLIENT][BUILD-PLAN]"
                {:active-count (count active-states)
                 :ops-count (count ops)
                 :beam-ops @beam-count*
                 :ring-ops @ring-count*
                 :first-op (first ops)}))
    (when (seq ops)
      {:ops ops})))

(defmethod arc-beam/effect-initial-state [:current-charging :level] [_ _] {:states {}})
(defmethod arc-beam/effect-enqueue-state! [:current-charging :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(defmethod arc-beam/effect-tick-state! [:current-charging :level] [_ _ store] (tick-state! store))
(defmethod arc-beam/effect-build-plan :current-charging
  [_effect-id camera-pos hand-center-pos tick & _more]
  (build-plan camera-pos hand-center-pos tick))
(defmethod arc-beam/effect-clear-owner! :current-charging [_ store owner-key]
  (assoc store :states (dissoc (:states store) owner-key)))
