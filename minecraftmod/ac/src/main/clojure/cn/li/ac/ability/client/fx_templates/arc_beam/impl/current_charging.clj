(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.current-charging
  (:require [cn.li.ac.ability.client.effects.beam-ops :as fx-beam]
            [cn.li.ac.ability.client.effects.rv3 :as rv3]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
            [cn.li.ac.ability.client.level-effects :as level-effects]
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

(def ^:private charging-beam-style
  {:width 0.08
   :core-width 0.03
   :outer-color {:r 108 :g 228 :b 255 :a 120}
   :inner-color {:r 225 :g 250 :b 255 :a 180}
   :line-color {:r 160 :g 238 :b 255 :a 140}})

;; Matches original's EntitySurroundArc, which is built from small arc-
;; textured strands, not a plain circle outline — reuse the same textured
;; billboard-beam renderer as the main charging beam (fx-beam/beam-ops)
;; instead of drawing debug line segments.
(def ^:private ring-arc-style
  {:width 0.05
   :core-width 0.02
   :outer-color {:r 150 :g 232 :b 255 :a 150}
   :inner-color {:r 235 :g 252 :b 255 :a 200}
   :line-color {:r 190 :g 244 :b 255 :a 170}})

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
  [cam-v cx y cz radius ticks segments style]
  (vec
    (for [idx (range segments)
          :let [a0 (/ (* 2.0 Math/PI idx) segments)
                a1 (/ (* 2.0 Math/PI (inc idx)) segments)
                jitter (* 0.06 (Math/sin (+ (* 0.31 (double ticks)) (* idx 1.7))))
                r0 (+ radius jitter)
                r1 (+ radius (- jitter))
                p0 (rv3/v3 (+ cx (* r0 (Math/cos a0))) y (+ cz (* r0 (Math/sin a0))))
                p1 (rv3/v3 (+ cx (* r1 (Math/cos a1))) y (+ cz (* r1 (Math/sin a1))))]]
      (fx-beam/beam-ops cam-v p0 p1 style))))

(defn- target-ring-ops
  [cam-v target ticks charge-ratio]
  (let [base-radius (+ 0.45 (* 0.25 (double charge-ratio)))
        pulse (+ base-radius (* 0.07 (Math/sin (* 0.24 (double ticks)))))
        tx (double (:x target))
        y (+ (double (:y target)) 0.05)
        tz (double (:z target))]
    (ring-arc-ops cam-v tx y tz pulse ticks 10 ring-arc-style)))

(defn- caster-ring-ops
  [cam-v caster-pos ticks]
  (let [radius 0.45
        pulse (+ radius (* 0.08 (Math/sin (* 0.22 (double ticks)))))
        cx (double (:x caster-pos))
        y (+ (double (:y caster-pos)) 0.9)
        cz (double (:z caster-pos))]
    (ring-arc-ops cam-v cx y cz pulse ticks 8 ring-arc-style)))

(defn- build-plan
  [camera-pos hand-center-pos tick]
  (let [store (:states (level-effects/effect-state-snapshot :current-charging))
        active-states (filter :active? (vals (or store {})))
        _ (when (zero? (mod (long (or tick 0)) 20))
            (log/info "[CC-TRACE][CLIENT][BUILD-PLAN]"
                      {:store-size (count store)
                       :active-count (count active-states)
                       :hand-center-pos (some? hand-center-pos)
                       :states (mapv (fn [st]
                                       {:active? (:active? st) :is-item (:is-item st)
                                        :good? (:good? st) :target (:target st)
                                        :caster-pos (:caster-pos st)})
                                     (vals (or store {})))}))
        cam-v (rv3/map->v3 camera-pos)
        ops (vec
              (mapcat
                (fn [st]
                  (let [own? (own-state? st hand-center-pos)
                        start (or (and own? hand-center-pos)
                                  (:caster-pos st)
                                  hand-center-pos)
                        target (:target st)
                        ticks (long (or (:charge-ticks st) 0))
                        ratio (double (or (:charge-ratio st) 0.0))
                        item? (boolean (:is-item st))
                        good? (boolean (:good? st))]
                    (concat
                      (when (and (not item?) (map? start) (map? target))
                        (fx-beam/beam-ops cam-v
                                          (rv3/map->v3 (dissoc start :player-uuid))
                                          (rv3/map->v3 target)
                                          charging-beam-style))
                      ;; Matches original: the surround ring only ever
                      ;; appears around the TARGET block once it's a valid
                      ;; energy receiver (block mode) — never around the
                      ;; caster in block mode.
                      (when (and (not item?) good? (map? target))
                        (target-ring-ops cam-v target ticks ratio))
                      ;; Item mode has no beam/target at all — original
                      ;; wraps the surround ring around the PLAYER instead
                      ;; (new EntitySurroundArc(player)).
                      (when (and item? (map? start))
                        (caster-ring-ops cam-v (dissoc start :player-uuid) ticks)))))
                active-states))]
    (when (seq ops)
      {:ops ops})))

(defmethod arc-beam/effect-initial-state [:current-charging :hand] [_ _] {:states {}})
(defmethod arc-beam/effect-enqueue-state! [:current-charging :hand]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(defmethod arc-beam/effect-tick-state! [:current-charging :hand] [_ _ store] (tick-state! store))
(defmethod arc-beam/effect-initial-state [:current-charging :level] [_ _] {:states {}})
(defmethod arc-beam/effect-enqueue-state! [:current-charging :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(defmethod arc-beam/effect-tick-state! [:current-charging :level] [_ _ store] (tick-state! store))
(defmethod arc-beam/effect-build-plan :current-charging
  [_effect-id camera-pos hand-center-pos tick & _more]
  (build-plan camera-pos hand-center-pos tick))
(defmethod arc-beam/effect-clear-owner! :current-charging [_ store owner-key]
  (assoc store :states (dissoc (:states store) owner-key)))
