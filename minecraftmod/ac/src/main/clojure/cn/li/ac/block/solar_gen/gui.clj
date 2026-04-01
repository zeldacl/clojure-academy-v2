(ns cn.li.ac.block.solar-gen.gui
  "CLIENT-ONLY: Solar Generator GUI implementation.

  This file contains:
  - GUI layout and component builders
  - GUI interaction logic
  - Container atom management
  - Message registration and network handlers

  Must be loaded via side-checked requiring-resolve from platform layer.

  Uses existing XML page: assets/my_mod/guis/rework/page_solar.xml
  and composes it into TechUI tabs (inv + wireless) with InfoArea."
  (:require [cn.li.mcmod.gui.cgui :as cgui]
            [cn.li.mcmod.gui.xml-parser :as cgui-doc]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.ac.gui.platform-adapter :as gui]
            [cn.li.mcmod.gui.tabbed-gui :as tabbed-gui]
            [cn.li.ac.gui.tech-ui-common :as tech-ui]
            [cn.li.ac.wireless.gui.tab :as wireless-tab]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.gui.slot-registry :as slot-registry]
            [cn.li.mcmod.gui.dsl :as gui-dsl]
            [cn.li.ac.energy.operations :as energy-ops]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.ac.wireless.gui.container.schema-runtime :as schema-runtime]
            [cn.li.mcmod.gui.container.schema :as schema]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.block.solar-gen.schema :as solar-schema]
            [cn.li.ac.registry.hooks :as hooks]
            [cn.li.mcmod.platform.position :as pos])
  (:import [cn.li.acapi.wireless IWirelessNode]))

;; ============================================================================
;; Slot Schema
;; ============================================================================

(def solar-gen-id :solar-gen)

(def solar-gen-slot-schema
  (slot-schema/register-slot-schema!
    {:schema-id solar-gen-id
     :slots [{:id :energy :type :energy :x 42 :y 81}]}))

;; ============================================================================
;; Message Registration
;; ============================================================================

(msg-registry/register-block-messages!
  :generator
  [:get-status :list-nodes :connect :disconnect])

(def gui-width tech-ui/gui-width)
(def gui-height tech-ui/gui-height)

;; ============================================================================
;; Field Schema (imported from schema.clj)
;; ============================================================================

;; ============================================================================
;; Container Creation (from solar_container.clj)
;; ============================================================================

(def ^:private solar-slot-schema-id solar-gen-id)

(defn create-container [tile player]
  (let [state (or (common/get-tile-state tile) {})]
    (merge {:tile-entity    tile
            :player         player
            :container-type :solar}
           (schema-runtime/build-gui-atoms solar-schema/unified-solar-schema state))))

(defn get-slot-count [_container]
  (slot-registry/get-slot-count solar-slot-schema-id))

(defn can-place-item? [_container _slot-index item-stack]
  (energy-ops/is-energy-item-supported? item-stack))

(defn get-slot-item [container slot-index]
  (common/get-slot-item-be container slot-index))

(defn set-slot-item! [container slot-index item-stack]
  (common/set-slot-item-be! container slot-index item-stack {} identity))

(defn slot-changed! [_container _slot-index] nil)

(defn still-valid? [_container _player] true)

(defn sync-to-client! [container]
  (let [state (or (common/get-tile-state (:tile-entity container)) {})
        new-energy (double (get state :energy 0.0))
        new-max-energy (double (get state :max-energy 1000.0))
        new-status (str (get state :status "STOPPED"))
        new-gen-speed (double (get state :gen-speed 0.0))]
    ;; Only update atoms if values changed
    (when (not= new-energy @(:energy container))
      (reset! (:energy container) new-energy))
    (when (not= new-max-energy @(:max-energy container))
      (reset! (:max-energy container) new-max-energy))
    (when (not= new-status @(:status container))
      (reset! (:status container) new-status))
    (when (not= new-gen-speed @(:gen-speed container))
      (reset! (:gen-speed container) new-gen-speed))))

(defn get-sync-data [container]
  ((schema-runtime/build-get-sync-data-fn solar-schema/unified-solar-schema) container))

(defn apply-sync-data! [container data]
  ((schema-runtime/build-apply-sync-data-fn solar-schema/unified-solar-schema) container data))

(defn tick! [container]
  (swap! (:sync-ticker container) inc)
  (sync-to-client! container))

(defn handle-button-click! [_container _button-id _player] nil)

(defn on-close [container]
  ((schema-runtime/build-on-close-fn solar-schema/unified-solar-schema) container))

;; ============================================================================
;; GUI Components
;; ============================================================================

(defn- status->frame-v0
  "Map Solar status string to v0 for 3-frame vertical texture."
  [status]
  (case status
    "STOPPED" (/ 1.0 3.0)
    "STRONG" 0.0
    "WEAK" (/ 2.0 3.0)
    (/ 1.0 3.0)))

(def ^:private effect-solar-texture
  (modid/asset-path "textures" "guis/effect/effect_solar.png"))

(defn- attach-anim-frame!
  "Attach per-frame UV update to `ui_block/anim_frame` so runtime renders 3-frame effect texture.
   Degrades gracefully if widget is missing."
  [inv-window container]
  (when-let [anim-frame (cgui/find-widget inv-window "ui_block/anim_frame")]
    (events/on-frame anim-frame
      (fn [_]
        (try
          (let [v0 (status->frame-v0 @(:status container))
                v1 (+ v0 (/ 1.0 3.0))]
            (comp/render-texture-region anim-frame effect-solar-texture 0 0 0 0 0.0 v0 1.0 v1))
          (catch Exception _e
            ;; Keep UI alive even if texture path/resource fails at runtime.
            nil))))))

(declare create-wireless-panel)

(defn create-solar-gui
  "Create Solar Generator GUI root widget."
  [container _player & [opts]]
  (try
    (let [doc (cgui-doc/read-xml (modid/namespaced-path "guis/rework/page_solar.xml"))
          inv-window (cgui-doc/get-widget doc "main")
          wireless-window (wireless-tab/create-wireless-panel {:mode :generator :container container})
          inv-page {:id "inv" :window inv-window}
          wireless-page {:id "wireless" :window wireless-window}
          pages [inv-page wireless-page]
          container-id (when-let [m (:menu opts)] (gui/get-menu-container-id m))
          tech-ui (apply tech-ui/create-tech-ui pages)
          _ (tabbed-gui/attach-tab-sync! pages tech-ui container container-id)
          main-widget (:window tech-ui)
          info-area (tech-ui/create-info-area)
          max-e (fn [] (max 1.0 (double @(:max-energy container))))
          speed-str (fn []
                      (try
                        (format "%.2fIF/T" (double @(:gen-speed container)))
                        (catch Exception _ "0.00IF/T")))]

      (attach-anim-frame! inv-window container)

      (cgui/set-position! info-area (+ (cgui/get-width inv-window) 7) 5)
      (tech-ui/reset-info-area! info-area)
      (let [y (tech-ui/add-histogram
                info-area
                [(tech-ui/hist-buffer (fn [] (double @(:energy container)))
                                      (max-e))]
                0)
            y (tech-ui/add-sepline info-area "Info" y)
            y (tech-ui/add-property info-area "gen_speed" speed-str y)
            y (tech-ui/add-property info-area "status" (fn [] @(:status container)) y)]
        y)

      (cgui/add-widget! main-widget info-area)

      (log/info "Created Solar Generator GUI (TechUI)")
      (if (:menu opts)
        {:root main-widget :current (:current tech-ui)}
        main-widget))
    (catch Exception e
      (log/error "Error creating Solar GUI:"(ex-message e))
      (throw e))))

(defn create-screen
  "Create CGuiScreenContainer for Solar GUI."
  [container minecraft-container player]
  (let [gui (create-solar-gui container player {:menu minecraft-container})
        root (if (map? gui) (:root gui) gui)
        base (cgui/create-cgui-screen-container root minecraft-container)]
    (if (map? gui)
      (tech-ui/assoc-tech-ui-screen-size (assoc base :current-tab-atom (:current gui)))
      (tech-ui/assoc-tech-ui-screen-size base))))

;; ============================================================================
;; GUI Registration
;; ============================================================================

(defn- solar-container? [container]
  (and (map? container)
       (contains? container :tile-entity)
       (contains? container :energy)
       (contains? container :status)))

(def ^:private solar-slot-layout
  (slot-schema/get-slot-layout solar-gen-id))

(gui-dsl/defgui-with-lazy-fns solar-gen
  :gui-id 2
  :namespace 'cn.li.ac.block.solar-gen.gui
  :display-name "Solar Generator"
  :gui-type :solar
  :registry-name "solar_gen_gui"
  :screen-factory-fn-kw :create-solar-screen
  :slot-layout solar-slot-layout
  :container-predicate solar-container?)

;; ============================================================================
;; Auto-Registration Hooks
;; ============================================================================

;; Register client renderer
(hooks/register-client-renderer! 'cn.li.ac.block.solar-gen.render/init!)

