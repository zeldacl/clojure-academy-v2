(ns cn.li.ac.ability.client.reactive-hud
  "Reactive HUD snapshot — independent of build-client-overlay-plan.
   Reads player projection + hud.clj builders; no element-vector plan."
  (:require [cn.li.ac.ability.client.debug-overlay :as debug-overlay]
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
            [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.i18n :as i18n]))

(def ^:private rh-path [:service :reactive-hud])
(def ^:private cui-path [:service :client-ui :runtime]) ; shared client-ui runtime atom leaf (see client-ui-hooks)
(def ^:private vm-wave-glow "my_mod:textures/effects/glow_circle.png")
(def ^:private coin-dot-count 12)

(defn- owner-key [player-uuid]
  (read-model/owner-key {:player-uuid player-uuid} nil))

(defn- rh-state-atom []
  (if-let [fw-atom (fw/fw-atom)]
    (or (get-in @fw-atom rh-path)
        (let [a (atom {:vm-wave-circles {} :vm-wave-last-spawn-ms {}})]
          (swap! fw-atom assoc-in rh-path a)
          a))
    (atom {:vm-wave-circles {} :vm-wave-last-spawn-ms {}})))

(defn- update-rh-state! [f & args]
  (apply swap! (rh-state-atom) f args))

(defn- client-ui-runtime-atom []
  (when-let [fw-atom (fw/fw-atom)]
    (get-in @fw-atom cui-path)))

(defn- charge-coin-state-snapshot []
  (if-let [a (client-ui-runtime-atom)]
    (:charge-coin-state @a {})
    {}))

(defn- dissoc-charge-coin-owner! [ok]
  (when-let [a (client-ui-runtime-atom)]
    (swap! a update :charge-coin-state (fn [m] (dissoc (or m {}) ok)))))

(defn- ease-in-out [t]
  (let [t (max 0.0 (min 1.0 (double t)))]
  (* t t (- 3.0 (* 2.0 t)))))

(defn- railgun-charge-item-max-ticks []
  (skill-config/tunable-int :railgun :charge.item-charge-ticks))

(defn- railgun-coin-active-threshold []
  (skill-config/tunable-double :railgun :qte.coin-active-threshold))

(defn- railgun-coin-window-ms []
  (skill-config/tunable-int :railgun :qte.coin-window-ms))

(defn- coin-qte-visual-state [player-uuid now-ms]
  (let [contexts (read-model/get-player-contexts-for-player player-uuid)
        railgun-ctx (some (fn [ctx-data]
                            (when (and (= :railgun (:skill-id ctx-data))
                                       (ctx/active-context? ctx-data))
                              ctx-data))
                          contexts)
        skill-state (:skill-state railgun-ctx)
        mode (:mode skill-state)
        charge-ticks (max 0 (int (or (:charge-ticks skill-state) 0)))
        max-charge-ticks (max 1 (int (railgun-charge-item-max-ticks)))]
    (if (= mode :item-charge)
      {:active? true
       :coin-active? false
       :coin-progress 0.0
       :charge-ratio (max 0.0 (min 1.0 (- 1.0 (/ (double charge-ticks) max-charge-ticks))))}
      (let [ok (owner-key player-uuid)
            coin-state (or (get (charge-coin-state-snapshot) ok) {})
            {:keys [start-ms window-ms]} coin-state
            has-window? (and start-ms window-ms)
            elapsed (if has-window? (- (long now-ms) (long start-ms)) 0)
            progress (if has-window?
                       (/ (double (max 0 elapsed)) (double (max 1 (long window-ms))))
                       0.0)
            active-window? (and has-window? (<= progress 1.0))
            ratio (max 0.0 (min 1.0 progress))
            coin-active? (and active-window? (>= ratio (railgun-coin-active-threshold)))]
        (when (and has-window? (not active-window?))
          (dissoc-charge-coin-owner! ok))
        {:active? (boolean active-window?)
         :coin-active? (boolean coin-active?)
         :coin-progress ratio}))))

(defn- build-charging-layer [player-uuid screen-w screen-h]
  (let [{:keys [active? blending? is-item good? charge-ticks charge-ratio]}
        (current-charging-fx/current-state player-uuid)
        visible? (or active? blending? (pos? (long (or charge-ticks 0))))]
    (when visible?
      (let [bar-width 140
            bar-height 8
            x (int (/ (- screen-w bar-width) 2))
            y (- screen-h 34)
            fill-width (max 2 (int (* bar-width (double (or charge-ratio 0.0)))))
            cx (int (/ screen-w 2))
            cy (int (/ screen-h 2))]
        {:dim-a (if active? 110 55)
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
  (let [ok (owner-key player-uuid)]
    (update-rh-state!
      (fn [state]
        (let [last-spawn-ms (long (get-in state [:vm-wave-last-spawn-ms ok] 0))
              needs-spawn? (and active? (>= (- now-ms last-spawn-ms) 90))
              circles (get-in state [:vm-wave-circles ok] [])
              alive (->> circles
                         (filter (fn [{:keys [born-ms life-ms]}]
                                   (< (- now-ms (long born-ms)) (long life-ms))))
                         vec)
              spawned (if needs-spawn?
                        (conj alive (spawn-vm-wave-circle screen-w screen-h now-ms))
                        alive)
              next-circles (if active? spawned (if (seq spawned) spawned []))
              state (if needs-spawn?
                      (assoc-in state [:vm-wave-last-spawn-ms ok] now-ms)
                      state)]
          (if (seq next-circles)
            (assoc-in state [:vm-wave-circles ok] next-circles)
            (update state :vm-wave-circles dissoc ok))))))
  nil)

(defn seed-vm-wave-state-for-test!
  ([owner circles]
   (seed-vm-wave-state-for-test! owner circles 0))
  ([owner circles last-spawn-ms]
   (let [ok (read-model/owner-key owner nil)]
     (update-rh-state!
       (fn [state]
         (-> state
             (assoc-in [:vm-wave-circles ok] (vec circles))
             (assoc-in [:vm-wave-last-spawn-ms ok] (long last-spawn-ms)))))
     nil)))

(defn clear-vm-wave-for-owner!
  [owner-key]
  (update-rh-state!
    (fn [state]
      (-> state
          (update :vm-wave-circles dissoc owner-key)
          (update :vm-wave-last-spawn-ms dissoc owner-key))))
  nil)

(defn build-vm-wave-items
  "Reactive VM wave circle items for one frame."
  [player-uuid now-ms tint]
  (when tint
    (->> (get-in @(rh-state-atom) [:vm-wave-circles (owner-key player-uuid)] [])
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

(defn- scan-vm-state [player-uuid]
  (reduce
    (fn [acc [_ctx-id ctx-data]]
      (if (and (= (:player-uuid ctx-data) player-uuid)
               (ctx/active-context? ctx-data))
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
    (ctx/get-all-contexts)))

(defn build-snapshot
  "Reactive HUD snapshot for one frame.
   opts: {:activated-override :showing-numbers? :last-show-value-change-ms :active-overlay-app :now-ms}"
  [player-uuid screen-w screen-h opts]
  (let [now-ms (long (or (:now-ms opts) (System/currentTimeMillis)))
        ok (owner-key player-uuid)
        player-state (read-model/get-player-state ok)
        resource-data (:resource-data player-state)
        ability-data (:ability-data player-state)
        activated? (if (some? (:activated-override opts))
                     (boolean (:activated-override opts))
                     (boolean (:activated resource-data)))
        hud-model (build-hud-model player-state activated?)
        contexts (read-model/get-player-contexts-for-player player-uuid)
        hint (consumption-hint contexts)
        hud-model (if hint (assoc hud-model :consumption-hint hint) hud-model)
        cooldown-data (:cooldown-data player-state)
        showing-numbers? (boolean (:showing-numbers? opts false))
        last-show-ms (long (or (:last-show-value-change-ms opts) 0))
        preset-state (keybinds/get-preset-switch-state player-uuid)
        activate-hint (keybinds/get-activate-hint player-uuid)
        cp-bar (when (:activated hud-model) (hud/build-cp-bar-render-data hud-model))
        overload-bar (when (:activated hud-model)
                       (hud/build-overload-bar-render-data hud-model now-ms))
        skill-slots (when (:activated hud-model)
                      (-> (hud/build-skill-slot-shape hud-model screen-w screen-h)
                          (hud/patch-skill-slot-cooldown cooldown-data)
                          (hud/patch-skill-slot-visual contexts player-uuid)))
        preset-indicators (hud/build-preset-indicators-data preset-state now-ms)
        numbers-texts (hud/build-numbers-texts-data hud-model showing-numbers? last-show-ms now-ms)
        vm (scan-vm-state player-uuid)
        vm-tint (cond
                  (:reflection-active? vm) [70 179 255]
                  (:deviation-active? vm) [90 255 120]
                  :else nil)
        phase (double (/ (mod now-ms 1200) 1200.0))
        ol-pct (double (or (:percent overload-bar) 0.0))
        overlay-app (:active-overlay-app opts)]
    {:overlay-app overlay-app
     :overlay-app-ui (when overlay-app (build-overlay-app-ui overlay-app screen-w screen-h))
     :background-mask (background-mask resource-data ability-data activated?)
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
     :charging (build-charging-layer player-uuid screen-w screen-h)
     :coin-qte (build-coin-qte-layer player-uuid screen-w screen-h now-ms)
     :toasts (toast/build-toast-layouts screen-w screen-h now-ms)
     :tutorial-notification (tutorial-notification/build-notification-layout screen-w screen-h now-ms)
     :debug-lines (or (debug-overlay/build-debug-line-items player-state) [])
     :overload-pulse-intensity (when (> ol-pct 0.8) (* (- ol-pct 0.8) 5.0))
     :screen-w screen-w
     :screen-h screen-h}))
