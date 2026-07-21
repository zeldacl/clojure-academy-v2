(ns cn.li.ac.ability.client.reactive-hud
  "Reactive HUD snapshot — independent of build-client-overlay-plan.
   Reads player projection + hud.clj builders; no element-vector plan."
  (:require
            [cn.li.ac.config.modid :as modid] [cn.li.ac.ability.client.debug-overlay :as debug-overlay]
            [cn.li.ac.ability.client.hud :as hud]
            [cn.li.ac.ability.client.keybinds :as keybinds]
            [cn.li.ac.ability.client.read-model :as read-model]
            [cn.li.ac.ability.model.preset :as preset-data]
            [cn.li.ac.ability.registry.category :as category]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.util.toggle :as toggle]
            [cn.li.ac.client.toast :as toast]
            [cn.li.ac.content.ability.electromaster.current-charging-fx :as current-charging-fx]
            [cn.li.ac.tutorial.client.notification :as tutorial-notification]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.i18n :as i18n])
  (:import [java.util ArrayList HashMap]))

(def ^:private vm-wave-glow (modid/asset-path "textures" "effects/glow_circle.png"))
(def ^:private coin-dot-count 12)

(defn- owner-key [player-uuid]
  (read-model/owner-key {:player-uuid player-uuid} nil))

(defonce ^:private ^HashMap vm-waves-by-owner (HashMap.))
(defonce ^:private ^HashMap vm-wave-spawn-by-owner (HashMap.))

;; ============================================================================
;; Charging arc particles — port of upstream CurrentChargingHUD/SubArc2D
;; (electromaster BodyIntensify "current charging" full-screen release cue):
;; a small ring of flickering arc sprites around the screen center, growing
;; into a denser burst the instant the charge is released.
;; ============================================================================

(def ^:private arc-frame-count 10)

(defn- arc-frame-src [idx]
  (modid/asset-path "textures" (str "effects/arcs/" idx ".png")))

(def ^:private arc-frame-srcs (mapv arc-frame-src (range arc-frame-count)))

(defonce ^:private ^HashMap arc-particles-by-owner (HashMap.))
(defonce ^:private ^HashMap arc-prior-active-by-owner (HashMap.))
;; started-ms/blend-out-ms per owner, driving the mask fade (BLEND_TIME/
;; BLEND_OUT_TIME below) — separate map/concern from the particle list above.
(defonce ^:private ^HashMap charging-fade-session-by-owner (HashMap.))

(defn- gen-arc
  "One SubArc2D: fixed (x,y) in [-1,1] screen-fraction coords (phi·sin/cosθ),
   fixed size, ticked texture-frame flicker + on/off flicker."
  [phi-lo phi-hi size-lo size-hi life frame-rate switch-rate]
  (let [phi (+ phi-lo (* (rand) (- phi-hi phi-lo)))
        theta (* (rand) 2.0 Math/PI)]
    {:x (* phi (Math/sin theta))
     :y (* phi (Math/cos theta))
     :size (+ size-lo (* (rand) (- size-hi size-lo)))
     :tex-idx (rand-int arc-frame-count)
     :tick 0
     :life (long life)
     :frame-rate (double frame-rate)
     :switch-rate (double switch-rate)
     :draw? true
     :dead? false}))

(defn- tick-arc
  "Port of SubArc2D.tick(): stochastic frame reroll, tick-with-90%-chance
   life countdown, and (for switch-rate>0 burst arcs) on/off flicker."
  [{:keys [tick life frame-rate switch-rate draw?] :as arc}]
  (let [tex-idx' (if (< (rand) (* 0.5 frame-rate)) (rand-int arc-frame-count) (:tex-idx arc))
        tick' (if (< (rand) 0.9) (inc (long tick)) (long tick))
        dead? (>= tick' (long life))
        draw?' (cond
                 (and draw? (< (rand) (* 0.4 switch-rate))) false
                 (and (not draw?) (< (rand) (* 0.3 switch-rate))) true
                 :else draw?)]
    (assoc arc :tex-idx tex-idx' :tick tick' :dead? dead? :draw? draw?')))

(defn tick-charging-arcs!
  "Advance the arc-particle lifecycle for one player (client tick hook).
   Mirrors upstream SubArcHandler2D.tick() plus CurrentChargingHUD's spawn
   rules: an idle ring (5-7 arcs, phi 0.84-0.96) while charging, and — on the
   active? rising edge (the moment of release, matching upstream startBlend
   from BodyIntensify) — a denser burst (10-15 arcs, phi 0.6-1, life 25 ticks)."
  [player-uuid]
  (let [ok (owner-key player-uuid)
        {:keys [active? blending? charge-ticks good?]} (current-charging-fx/current-state player-uuid)
        charging? (or active? blending? (pos? (long (or charge-ticks 0))))
        prior-active? (boolean (.get arc-prior-active-by-owner ok))
        ^ArrayList particles (.get arc-particles-by-owner ok)]
    (.put arc-prior-active-by-owner ok (boolean active?))
    (cond
      (and charging? (nil? particles))
      (let [n (+ 5 (rand-int 3))
            fresh (ArrayList.)]
        (dotimes [_ n]
          (.add fresh (gen-arc 0.84 0.96 25.0 30.0 233333 0.3 0.0)))
        (.put arc-particles-by-owner ok fresh))

      (and (not charging?) particles)
      (.remove arc-particles-by-owner ok)

      (and charging? particles)
      (let [pending (ArrayList. ^java.util.Collection particles)]
        (when (and active? (not prior-active?) good?)
          (dotimes [_ (+ 10 (rand-int 6))]
            (.add pending (gen-arc 0.6 1.0 35.0 40.0 25 0.3 0.2))))
        (.clear particles)
        (doseq [arc pending]
          (let [arc' (tick-arc arc)]
            (when-not (:dead? arc') (.add particles arc')))))))
  nil)

(defn clear-charging-arcs-for-owner!
  [owner-key]
  (.remove arc-particles-by-owner owner-key)
  (.remove arc-prior-active-by-owner owner-key)
  (.remove charging-fade-session-by-owner owner-key)
  nil)

(defn- charging-mask-alpha
  "mAlpha: fade in over BLEND_TIME (500ms) since session start, held at 1.0
   while charging, then fade out over BLEND_OUT_TIME (200ms) once blending."
  ^double [session ^long now-ms]
  (if (nil? session)
    0.0
    (let [{:keys [started-ms blend-out-ms]} session]
      (if blend-out-ms
        (Math/max 0.0 (- 1.0 (/ (- now-ms (long blend-out-ms)) 200.0)))
        (Math/min 1.0 (/ (- now-ms (long started-ms)) 500.0))))))

(defn- update-charging-fade-session!
  "Upstream getTimeActive()/blendTime state machine: track when this charging
   session started (drives the 500ms fade-in) and when it began blending out
   (drives the 200ms fade-out), matching CurrentChargingHUD field semantics.
   The session lingers past charging?→false until its own fade-out finishes
   (started immediately if the game state never reports an explicit blending?
   phase), so the mask/arcs always get their full local fade-out."
  [ok charging? blending? now-ms]
  (when-let [session (.get charging-fade-session-by-owner ok)]
    (when (and (or blending? (not charging?)) (nil? (:blend-out-ms session)))
      (.put charging-fade-session-by-owner ok (assoc session :blend-out-ms now-ms))))
  (when (and charging? (nil? (.get charging-fade-session-by-owner ok)))
    (.put charging-fade-session-by-owner ok {:started-ms now-ms :blend-out-ms nil}))
  (let [session' (.get charging-fade-session-by-owner ok)]
    (when (and session' (not charging?) (<= (charging-mask-alpha session' now-ms) 0.0))
      (.remove charging-fade-session-by-owner ok))
    (.get charging-fade-session-by-owner ok)))

(defn- build-arc-particle-items
  "Reactive arc-particle sprites for one frame — screen position derived from
   each arc's fixed [-1,1] fraction, matching upstream's
   width/2 + xScale*x - size/2 (xScale = width/2)."
  [player-uuid screen-w screen-h]
  (let [ok (owner-key player-uuid)
        ^ArrayList particles (.get arc-particles-by-owner ok)
        blending? (boolean (:blending? (current-charging-fx/current-state player-uuid)))
        alpha (if blending? 0.4 0.3)
        hw (/ (double screen-w) 2.0)
        hh (/ (double screen-h) 2.0)]
    (when particles
      (->> particles
           (filter :draw?)
           (mapv (fn [{:keys [x y size tex-idx]}]
                   {:src (nth arc-frame-srcs tex-idx)
                    :x (int (- (+ hw (* hw (double x))) (/ size 2.0)))
                    :y (int (- (+ hh (* hh (double y))) (/ size 2.0)))
                    :w (int size)
                    :h (int size)
                    :alpha alpha
                    :tint nil}))))))

(defn- ease-in-out [t]
  (let [t (max 0.0 (min 1.0 (double t)))]
  (* t t (- 3.0 (* 2.0 t)))))

(defn- railgun-coin-active-threshold []
  (skill-config/tunable-double :railgun :qte.coin-active-threshold))

(defn- coin-qte-visual-state [player-uuid now-ms]
  (or (bridge/call-adapter :client-visual-state :ac/charge-coin
                           {:player-uuid player-uuid :now-ms now-ms})
      {:active? false :coin-active? false :coin-progress 0.0}))

(defn- build-charging-layer [player-uuid screen-w screen-h now-ms]
  (let [ok (owner-key player-uuid)
        {:keys [active? blending? is-item good? charge-ticks charge-ratio]}
        (current-charging-fx/current-state player-uuid)
        charging? (or active? blending? (pos? (long (or charge-ticks 0))))
        session (update-charging-fade-session! ok charging? blending? now-ms)
        visible? (or charging? session)]
    (when visible?
      (let [mask-alpha (charging-mask-alpha session now-ms)
            bar-width 140
            bar-height 8
            x (int (/ (- screen-w bar-width) 2))
            y (- screen-h 34)
            fill-width (max 2 (int (* bar-width (double (or charge-ratio 0.0)))))
            cx (int (/ screen-w 2))
            cy (int (/ screen-h 2))]
        {;; Upstream CurrentChargingHUD: black mask alpha = 0.1*mAlpha.
         :dim-a (int (* 0.1 mask-alpha 255.0))
         :mask-alpha mask-alpha
         :bar {:x x :y y :w bar-width :h bar-height :fill-w fill-width
               :backdrop (if is-item
                            {:r 12 :g 24 :b 48 :a 150}
                            {:r 8 :g 18 :b 36 :a 150})
               :accent (if good?
                         {:r 90 :g 210 :b 255 :a 200}
                         {:r 255 :g 190 :b 90 :a 200})}
         :label {:x (- cx 55) :y (- y 12)
                 :text (i18n/translate (if is-item "ac.current_charging.item" "ac.current_charging.block"))
                 :color {:r 255 :g 255 :b 255 :a 240}}
         :crosshair {:cx cx :cy cy :active? (boolean active?)}}))))

(defn- build-coin-qte-layer [player-uuid screen-w screen-h now-ms]
  (let [coin-state (coin-qte-visual-state player-uuid now-ms)]
    (when (and (:active? coin-state) (pos? (:coin-progress coin-state)))
      (let [cx (int (/ screen-w 2))
            cy (int (/ screen-h 2))
            progress (double (:coin-progress coin-state))
            coin-active? (boolean (:coin-active? coin-state))
            threshold (double (railgun-coin-active-threshold))
            ring-radius 24
            dot-size 3
            window-color (if coin-active?
                           {:r 255 :g 215 :b 0 :a 220}
                           {:r 180 :g 150 :b 50 :a 160})
            threshold-color {:r 255 :g 220 :b 80 :a 240}
            bg-color {:r 20 :g 18 :b 10 :a 100}
            dots (for [i (range coin-dot-count)
                       :let [angle (* 2.0 Math/PI (/ i coin-dot-count))
                             dot-active? (< (/ i coin-dot-count) progress)
                             dx (int (* ring-radius (Math/cos angle)))
                             dy (int (* ring-radius (Math/sin angle)))]]
                   {:x (+ cx dx (- dot-size))
                    :y (+ cy dy (- dot-size))
                    :w (* 2 dot-size)
                    :h (* 2 dot-size)
                    :color (if dot-active?
                             (update window-color :a #(int (* % (if coin-active? 1.0 0.6))))
                             (assoc window-color :a 40))})
            threshold-angle (* 2.0 Math/PI threshold)
            tx (int (* ring-radius (Math/cos threshold-angle)))
            ty (int (* ring-radius (Math/sin threshold-angle)))
            marker-size 2]
        {:cx cx :cy cy
         :bg-disc {:x (- cx ring-radius) :y (- cy ring-radius)
                   :w (* 2 ring-radius) :h (* 2 ring-radius) :color bg-color}
         :dots dots
         :marker {:x (+ cx tx (- marker-size)) :y (+ cy ty (- marker-size))
                  :w (* 2 marker-size) :h (* 2 marker-size) :color threshold-color}
         :pct-text {:x (- cx 14) :y (- cy 4)
                    :text (str (int (* 100.0 progress)) "%")
                    :color (if coin-active?
                             {:r 255 :g 215 :b 0 :a 255}
                             {:r 180 :g 150 :b 50 :a 200})}}))))

(defn- spawn-vm-wave-circle [screen-w screen-h now-ms]
  (let [cx (/ (double screen-w) 2.0)
        cy (/ (double screen-h) 2.0)
        offset-r (+ 8.0 (* (rand) 42.0))
        angle (* (rand) 2.0 Math/PI)
        life-ms (+ 520 (rand-int 260))
        start-size (+ 8.0 (* (rand) 6.0))
        end-size (+ 36.0 (* (rand) 32.0))]
    {:x (+ cx (* offset-r (Math/cos angle)))
     :y (+ cy (* offset-r (Math/sin angle)))
     :born-ms now-ms
     :life-ms life-ms
     :start-size start-size
     :end-size end-size
     :seed (rand)}))

(defn tick-vm-wave!
  "Advance VM wave circle lifecycle (client tick hook)."
  [player-uuid active? screen-w screen-h now-ms]
  (let [ok (owner-key player-uuid)
        ^ArrayList circles (or (.get vm-waves-by-owner ok)
                               (when active?
                                 (let [created (ArrayList.)]
                                   (.put vm-waves-by-owner ok created)
                                   created)))]
    (when circles
      (loop [i (dec (.size circles))]
        (when (>= i 0)
          (let [{:keys [born-ms life-ms]} (.get circles i)]
            (when (>= (- now-ms (long born-ms)) (long life-ms))
              (.remove circles (int i))))
          (recur (dec i))))
      (let [last-spawn-ms (long (or (.get vm-wave-spawn-by-owner ok) 0))]
        (when (and active? (>= (- now-ms last-spawn-ms) 90))
          (.add circles (spawn-vm-wave-circle screen-w screen-h now-ms))
          (.put vm-wave-spawn-by-owner ok (long now-ms))))
      (when (.isEmpty circles)
        (.remove vm-waves-by-owner ok))))
  nil)

(defn seed-vm-wave-state-for-test!
  ([owner circles]
   (seed-vm-wave-state-for-test! owner circles 0))
  ([owner circles last-spawn-ms]
   (let [ok (read-model/owner-key owner nil)]
     (.put vm-waves-by-owner ok (ArrayList. ^java.util.Collection circles))
     (.put vm-wave-spawn-by-owner ok (long last-spawn-ms))
     nil)))

(defn clear-vm-wave-for-owner!
  [owner-key]
  (.remove vm-waves-by-owner owner-key)
  (.remove vm-wave-spawn-by-owner owner-key)
  nil)

(defn build-vm-wave-items
  "Reactive VM wave circle items for one frame."
  [player-uuid now-ms tint]
  (when tint
    (->> (or (.get vm-waves-by-owner (owner-key player-uuid)) [])
         (map (fn [{:keys [x y born-ms life-ms start-size end-size seed]}]
                (let [elapsed (double (max 0 (- now-ms (long born-ms))))
                      life (double (max 1 life-ms))
                      t (min 1.0 (/ elapsed life))
                      s (+ start-size (* (- end-size start-size) t))
                      fade-in (min 1.0 (/ t 0.2))
                      fade-out (if (> t 0.6) (/ (- 1.0 t) 0.4) 1.0)
                      pulse (+ 0.85 (* 0.15 (Math/sin (+ (* t 12.0) (* seed Math/PI)))))
                      alpha (* 0.72 (ease-in-out t) fade-in fade-out pulse)
                      hs (/ s 2.0)]
                  {:src vm-wave-glow
                   :x (int (- x hs))
                   :y (int (- y hs))
                   :w (int s)
                   :h (int s)
                   :alpha (double (max 0.0 (min 1.0 alpha)))
                   :tint tint})))
         (filter #(pos? (:alpha %)))
         vec)))

(defn build-vm-wave-overlay-elements
  "Plan-path bridge for tests — returns :blit-texture element maps."
  [player-uuid now-ms tint]
  (mapv (fn [item]
          (-> item
              (assoc :kind :blit-texture :texture (:src item))
              (dissoc :src)))
        (or (build-vm-wave-items player-uuid now-ms tint) [])))

(defn- build-overlay-app-ui [app screen-w screen-h]
  (case app
    :freq-tx {:panel {:x 0 :y 0 :w 640 :h 480 :color {:r 32 :g 32 :b 32 :a 192}}
              :title {:x 200 :y 10 :text "Frequency Transmitter (Overlay)" :color 0xFFFFFFFF}
              :subtitle {:x 200 :y 30 :text "Press ESC to close" :color 0xFF888888}}
    :install-fx (let [cx (quot screen-w 2) cy (quot screen-h 2)]
                  {:panel {:x (- cx 150) :y (- cy 20) :w 300 :h 40 :color {:r 32 :g 32 :b 32 :a 192}}
                   :title {:x (- cx 60) :y (- cy 5) :text "Installing terminal..." :color 0xFFFFFFFF}})
    nil))

(defn- build-hud-model [player-state activated?]
  (when player-state
    (let [resource-data (:resource-data player-state)
          ability-data (:ability-data player-state)
          preset-data-map (:preset-data player-state)
          category-id (:category-id ability-data)
          cat (when category-id (category/get-category category-id))]
      {:cp {:cur (double (or (:cur-cp resource-data) 0.0))
            :max (double (or (:max-cp resource-data) 1.0))}
       :overload {:cur (double (or (:cur-overload resource-data) 0.0))
                  :max (double (or (:max-overload resource-data) 1.0))
                  :fine (boolean (get resource-data :overload-fine true))}
       :active-slots (vec (preset-data/get-active-slots preset-data-map))
       :activated activated?
       :category-id category-id
       :category-color (:color cat)
       :category-icon (:icon cat)
       :interfered? (boolean (seq (:interferences resource-data)))})))

(defn- consumption-hint [contexts]
  (some
    (fn [ctx-data]
      (let [skill-id (:skill-id ctx-data)
            exp (double (or (:exp ctx-data) 0.0))]
        (some
          (fn [cost-path]
            (try
              (let [cost (skill-config/lerp-double skill-id cost-path exp)]
                (when (pos? cost) (double cost)))
              (catch Throwable _ nil)))
          [:cost.tick.cp :cost.down.cp :cost.up.cp :cost.release.cp :cost.attack.cp])))
    (filter ctx/active-context? contexts)))

(defn- background-mask [resource-data ability-data activated?]
  (let [category-id (:category-id ability-data)
        cat (when category-id (category/get-category category-id))
        cat-color (:color cat)
        overloaded? (not (get resource-data :overload-fine true))]
    (cond
      overloaded? {:r 0.82 :g 0.08 :b 0.08 :a 0.65}
      (and activated? cat-color) {:r (double (nth cat-color 0))
                                  :g (double (nth cat-color 1))
                                  :b (double (nth cat-color 2))
                                  :a 0.35}
      :else {:r 0.0 :g 0.0 :b 0.0 :a 0.0})))

(defonce ^:private ^HashMap snapshot-cache-by-owner (HashMap.))

(defn- cached-frame-inputs
  "Recompute contexts/hud-model/background-mask/skill-slot-shape only when
  their pure inputs actually changed instead of every render frame.

  Two independent sub-keys, matching the split already proven correct for
  the (now-superseded) client-ui-hooks overlay-plan cache:
  - contexts/hud-model/background-mask key off whole player-state identity —
    resource-data (cp/overload) can legitimately change every server tick via
    continuous regen, and hud-model must reflect that, so this cache mostly
    saves the several render frames between two server ticks, not ticks
    themselves.
  - skill-slot-shape (registry/skill lookups) keys off preset-data identity
    alone, per build-skill-slot-shape's own documented contract: it depends
    only on active-slots (preset-data), not cooldown/context/resource data,
    so it must not be invalidated by cp/overload ticking or cooldown countdown.

  Server sync applies a whole-map replaceState on any player-state change
  (see runtime-store/set-player-state!), so `identical?` on player-state (and
  on the preset-data value nested within it) is a valid, zero-cost change
  token: guaranteed stale exactly when the underlying value would differ.
  Returns [contexts hud-model background-mask skill-slot-shape]."
  [ok player-uuid player-state activated? screen-w screen-h]
  (let [^objects prev (.get snapshot-cache-by-owner ok)
        preset-data (:preset-data player-state)
        state-fresh? (and prev (identical? (aget prev 0) player-state) (= (aget prev 1) activated?))
        [contexts hud-model bg-mask]
        (if state-fresh?
          [(aget prev 2) (aget prev 3) (aget prev 4)]
          (let [contexts (read-model/get-player-contexts-for-player player-uuid)
                hint (consumption-hint contexts)
                hud-model (cond-> (build-hud-model player-state activated?)
                            hint (assoc :consumption-hint hint))]
            [contexts hud-model
             (background-mask (:resource-data player-state) (:ability-data player-state) activated?)]))
        slots-fresh? (and prev (identical? (aget prev 5) preset-data)
                         (= (aget prev 6) screen-w) (= (aget prev 7) screen-h)
                         (= (boolean (:activated hud-model)) (boolean (aget prev 8))))
        skill-slot-shape (if slots-fresh?
                           (aget prev 9)
                           (when (:activated hud-model)
                             (hud/build-skill-slot-shape hud-model screen-w screen-h)))
        entry (object-array [player-state activated? contexts hud-model bg-mask
                             preset-data screen-w screen-h (boolean (:activated hud-model)) skill-slot-shape])]
    (.put snapshot-cache-by-owner ok entry)
    [contexts hud-model bg-mask skill-slot-shape]))

(defn clear-snapshot-cache-for-owner!
  [owner-key]
  (.remove snapshot-cache-by-owner owner-key)
  nil)

(defn- scan-vm-state
  "Reduce over an already-fetched contexts list (see cached-frame-inputs) —
  callers must not re-fetch via read-model here, since build-snapshot's caller
  already pays for that fetch once per underlying player-state change."
  [contexts]
  (reduce
    (fn [acc ctx-data]
      (if (ctx/active-context? ctx-data)
        (cond-> acc
          (toggle/is-toggle-active? ctx-data :vec-reflection)
          (-> (assoc :reflection-active? true)
              (assoc :reflection-intensity
                     (let [ticks (long (or (get-in ctx-data [:skill-state :toggle :vec-reflection :total-ticks]) 0))]
                       (double (min 1.0 (/ ticks 20.0))))))
          (toggle/is-toggle-active? ctx-data :vec-deviation)
          (assoc :deviation-active? true))
        acc))
    {:reflection-active? false :deviation-active? false :reflection-intensity 0.0}
    contexts))

(defn build-snapshot
  "Reactive HUD snapshot for one frame.
   opts: {:activated-override :showing-numbers? :last-show-value-change-ms :active-overlay-app :now-ms}"
  [player-uuid screen-w screen-h opts]
  (let [now-ms (long (or (:now-ms opts) (System/currentTimeMillis)))
        ok (owner-key player-uuid)
        player-state (read-model/get-player-state ok)
        resource-data (:resource-data player-state)
        activated? (if (some? (:activated-override opts))
                     (boolean (:activated-override opts))
                     (boolean (:activated resource-data)))
        [contexts hud-model bg-mask skill-slot-shape]
        (cached-frame-inputs ok player-uuid player-state activated? screen-w screen-h)
        cooldown-data (:cooldown-data player-state)
        showing-numbers? (boolean (:showing-numbers? opts false))
        last-show-ms (long (or (:last-show-value-change-ms opts) 0))
        preset-state (keybinds/get-preset-switch-state player-uuid)
        activate-hint (keybinds/get-activate-hint player-uuid)
        cp-bar (when (:activated hud-model) (hud/build-cp-bar-render-data hud-model))
        overload-bar (when (:activated hud-model)
                       (hud/build-overload-bar-render-data hud-model now-ms))
        skill-slots (when skill-slot-shape
                      (-> skill-slot-shape
                          (hud/patch-skill-slot-cooldown cooldown-data)
                          (hud/patch-skill-slot-visual contexts player-uuid)))
        preset-indicators (hud/build-preset-indicators-data preset-state now-ms)
        numbers-texts (hud/build-numbers-texts-data hud-model showing-numbers? last-show-ms now-ms)
        vm (scan-vm-state contexts)
        vm-tint (cond
                  (:reflection-active? vm) [70 179 255]
                  (:deviation-active? vm) [90 255 120]
                  :else nil)
        phase (double (/ (mod now-ms 1200) 1200.0))
        ol-pct (double (or (:percent overload-bar) 0.0))
        overlay-app (:active-overlay-app opts)]
    {:overlay-app overlay-app
     :overlay-app-ui (when overlay-app (build-overlay-app-ui overlay-app screen-w screen-h))
     :background-mask bg-mask
     :interfered? (boolean (seq (:interferences resource-data)))
     :activated? activated?
     :cp-bar cp-bar
     :overload-bar overload-bar
     :cp-full-glow? (boolean (:full-glow? cp-bar))
     :skill-slots (or skill-slots [])
     :activation-indicator (when (:activated hud-model)
                             (hud/build-activation-indicator-data hud-model activate-hint))
     :preset-indicators (or preset-indicators [])
     :numbers-texts (or numbers-texts [])
     :crosshair (when (:reflection-active? vm)
                  {:phase phase
                   :intensity (double (or (:reflection-intensity vm) 1.0))
                   :x (int (/ screen-w 2))
                   :y (int (/ screen-h 2))})
     :vm-waves (build-vm-wave-items player-uuid now-ms vm-tint)
     :charging (build-charging-layer player-uuid screen-w screen-h now-ms)
     :charging-arcs (or (build-arc-particle-items player-uuid screen-w screen-h) [])
     :coin-qte (build-coin-qte-layer player-uuid screen-w screen-h now-ms)
     :toasts (toast/build-toast-layouts screen-w screen-h now-ms)
     :tutorial-notification (tutorial-notification/build-notification-layout screen-w screen-h now-ms)
     :debug-lines (or (debug-overlay/build-debug-line-items player-state) [])
     :overload-pulse-intensity (when (> ol-pct 0.8) (* (- ol-pct 0.8) 5.0))
     :screen-w screen-w
     :screen-h screen-h}))
