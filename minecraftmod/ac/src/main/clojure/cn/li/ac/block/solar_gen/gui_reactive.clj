(ns cn.li.ac.block.solar-gen.gui-reactive
  "Solar Generator container registration through Presentation Runtime."
  (:require [cn.li.mcmod.runtime.install :as install]
            [cn.li.mcmod.gui.spec :as gui-reg]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.gui.manifest :as gui-manifest]
            [cn.li.ac.block.gui.sync :as gui-sync]
            [cn.li.ac.gui.presentation-container :as presentation-container]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.ac.block.solar-gen.schema :as solar-schema]))

(def solar-gen-id :solar-gen)
(def ^:private solar-sync (gui-sync/schema-sync-fns solar-schema/unified-solar-schema))

;; effect_solar.png — 104×210 sheet, 3 vertical frames (main page_solar + attach-anim-bind!).
;; Paths match wireless-node anim style so composite interning resolves textures.
(def ^:private effect-solar-texture "academy:textures/guis/effect/effect_solar.png")
(def ^:private page-windbase-texture "academy:textures/guis/ui/ui_windbase.png")
;; page_solar.xml: anim_frame at (56,23) size 104×70 scale 0.60 → 62.4×42.
(def ^:private anim-x 56.0)
(def ^:private anim-y 23.0)
(def ^:private anim-w (* 104.0 0.60))
(def ^:private anim-h (* 70.0 0.60))

(defn- value-of [container key default]
  (let [v (get container key default)]
    (if (instance? clojure.lang.IDeref v) @v v)))

(defn- solar-status->uv
  "Main solar-status->frame: STRONG=0, STOPPED=1/3, WEAK=2/3 (v0; tex-h 1/3)."
  [status]
  (case (str status)
    "STRONG" [0.0 (/ 1.0 3.0)]
    "WEAK" [(/ 2.0 3.0) 1.0]
    [(/ 1.0 3.0) (/ 2.0 3.0)]))

(defn- solar-page-composite
  "ui_windbase + status-selected effect_solar frame (main page_solar.xml).
   Both are page-composite items; machine_container paints them with 0×0
   composite cells so the strip overlays the page art."
  [status]
  (let [[v0 v1] (solar-status->uv status)]
    [{:kind :image
      :src page-windbase-texture
      :x 0.0 :y 0.0 :w 176.0 :h 187.0
      :rgba (unchecked-int 0xFFFFFFFF)}
     {:kind :image
      :src effect-solar-texture
      :x anim-x :y anim-y :w anim-w :h anim-h
      :u0 0.0 :v0 v0 :u1 1.0 :v1 v1
      :rgba (unchecked-int 0xFFFFFFFF)}]))

(defn create-container [tile player]
  (assoc (gui-sync/create-schema-container solar-schema/unified-solar-schema tile player :solar
                                           {:gui-id (gui-manifest/gui-id :solar-gen)})
         :presentation-close-fn (:on-close solar-sync)
         :presentation-tech-tabs? true
         :presentation-wireless {:domain :generator :role :generator}
         :presentation-wireless-state (atom {:linked nil :avail [] :password ""})
         ;; Status / gen-speed live in info-area via generic-info-area; page art
         ;; carries the status-keyed solar strip (not a time-loop animation).
         :presentation-snapshot-fn
         (fn [container _]
           {:page-composite (solar-page-composite (value-of container :status "STOPPED"))})))

(defn get-slot-count [_] (slot-schema/tile-slot-count solar-gen-id))
(defn can-place-item? [_ _ s] (energy/is-energy-item-supported? s))
(defn get-slot-item [c i] (common/get-slot-item-be c i))
(defn set-slot-item! [c i s] (common/set-slot-item-be! c i s {:inventory [nil]} identity))
(defn slot-changed! [_ _] nil)
(defn still-valid? [_ _] true)
(def server-menu-sync! (:server-menu-sync! solar-sync))
(def on-close (:on-close solar-sync))
(defn handle-button-click! [_ _ _] nil)

(defn create-screen [container menu player]
  (presentation-container/presentation-screen-data
    container menu player solar-gen-id "academy:machine_container"))

(defn- solar-container? [c]
  (and (map? c) (contains? c :tile-entity) (contains? c :energy) (contains? c :status)))

(defn init-solar-reactive! []
  (install/framework-once! ::solar-gui-reactive-installed?
    (fn []
      (slot-schema/register-slot-schema!
        ;; Match wind-gen-base / ui_windbase energy well (not phasegen's y 81).
        {:schema-id solar-gen-id :slots [{:id :energy :type :energy :x 42 :y 80}]})
      (gui-reg/register-block-gui!
        (gui-manifest/gui-name :solar-gen)
        (merge (gui-manifest/gui-registration :solar-gen)
               {:container-predicate solar-container?
                :container-fn create-container :screen-fn create-screen
                :server-menu-sync-fn server-menu-sync! :validate-fn still-valid?
                :close-fn on-close :button-click-fn handle-button-click!
                :slot-count-fn get-slot-count :slot-get-fn get-slot-item
                :slot-set-fn set-slot-item! :slot-can-place-fn can-place-item?
                :slot-changed-fn slot-changed!}))
      (log/info "Solar Generator GUI initialized (Presentation Runtime)"))))
