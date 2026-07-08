(ns cn.li.ac.ability.client.reactive-hud
  "Reactive HUD snapshot — independent of build-client-overlay-plan.
   Reads player projection + hud.clj builders; no element-vector plan."
  (:require [cn.li.ac.ability.client.hud :as hud]
            [cn.li.ac.ability.client.keybinds :as keybinds]
            [cn.li.ac.ability.client.read-model :as read-model]
            [cn.li.ac.ability.model.preset :as preset-data]
            [cn.li.ac.ability.registry.category :as category]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.util.toggle :as toggle]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- owner-key [player-uuid]
  [(runtime-hooks/*client-session-id*) nil (str player-uuid)])

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
        phase (double (/ (mod now-ms 1200) 1200.0))
        ol-pct (double (or (:percent overload-bar) 0.0))]
    {:overlay-app (:active-overlay-app opts)
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
     :overload-pulse-intensity (when (> ol-pct 0.8) (* (- ol-pct 0.8) 5.0))
     :screen-w screen-w
     :screen-h screen-h}))
