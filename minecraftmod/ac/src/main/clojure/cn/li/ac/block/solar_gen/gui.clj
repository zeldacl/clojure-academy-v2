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
  (:require [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.ac.gui.manifest :as gui-manifest]
            [cn.li.ac.gui.tech-ui-common :as tech-ui]
            [cn.li.ac.wireless.gui.tab :as wireless-tab]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.ac.energy.operations :as energy-ops]
            [cn.li.mcmod.gui.spec :as gui-reg]
            [cn.li.ac.block.gui.sync :as gui-sync]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.ac.block.solar-gen.schema :as solar-schema]))

;; ============================================================================
;; Slot Schema
;; ============================================================================

(def solar-gen-id :solar-gen)

(def gui-width tech-ui/gui-width)
(def gui-height tech-ui/gui-height)

;; ============================================================================
;; Field Schema (imported from schema.clj)
;; ============================================================================

;; ============================================================================
;; Container Creation (from solar_container.clj)
;; ============================================================================

(def ^:private solar-slot-schema-id solar-gen-id)
(def ^:private solar-sync
  (gui-sync/schema-sync-fns solar-schema/unified-solar-schema))

(defn create-container [tile player]
  (let [state (or (common/get-tile-state tile) {})]
    (gui-sync/create-schema-container solar-schema/unified-solar-schema tile player :solar {:state state})))

(defn get-slot-count [_container]
  (slot-schema/tile-slot-count solar-slot-schema-id))

(defn can-place-item? [_container _slot-index item-stack]
  (energy-ops/is-energy-item-supported? item-stack))

(defn get-slot-item [container slot-index]
  (common/get-slot-item-be container slot-index))

(defn set-slot-item! [container slot-index item-stack]
  (common/set-slot-item-be! container slot-index item-stack {} identity))

(defn slot-changed! [_container _slot-index] nil)

(defn still-valid? [_container _player] true)

(def sync-to-client! (:sync-to-client! solar-sync))
(def get-sync-data (:get-sync-data solar-sync))
(def apply-sync-data! (:apply-sync-data! solar-sync))

(defn tick! [container]
  (gui-sync/sync-tick! container sync-to-client! {:ticker-key :sync-ticker}))

(defn handle-button-click! [_container _button-id _player] nil)

(def on-close (:on-close solar-sync))

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

(defn- gen-msg [action]
  ((requiring-resolve 'cn.li.ac.wireless.gui.message.registry/msg) :generator action))

(defn- poll-status!
  "Send get-status query to server and update container atoms with response."
  [container tile]
  (let [owner (requiring-resolve 'cn.li.ac.wireless.gui.sync.handler/tile-pos-payload)]
    (when-let [payload (and owner (try (owner tile) (catch Exception _ nil)))]
      ((requiring-resolve 'cn.li.mcmod.network.client/send-to-server)
       (gen-msg :get-status)
       payload
       (fn [resp]
         (when resp
           (when-let [v (:energy resp)] (reset! (:energy container) (double v)))
           (when-let [v (:max-energy resp)] (reset! (:max-energy container) (double v)))
           (when-let [v (:status resp)] (reset! (:status container) (str v)))
           (when-let [v (:gen-speed resp)] (reset! (:gen-speed container) (double v)))))))))

(def ^:private poll-ticker (atom 0))

(defn- attach-anim-frame!
  "Attach per-frame UV update to `ui_block/anim_frame` and status poller."
  [inv-window container]
  ;; Status poller: query server every 20 frames for tile state
  (events/on-frame inv-window
    (fn [_]
      (swap! poll-ticker inc)
      (when (zero? (mod @poll-ticker 20))
        (poll-status! container (:tile-entity container)))))
  ;; Anim frame texture update
  (when-let [anim-frame (cgui-core/find-widget inv-window "ui_block/anim_frame")]
    (events/on-frame anim-frame
      (fn [_]
        (try
          (let [v0 (status->frame-v0 @(:status container))
                v1 (+ v0 (/ 1.0 3.0))]
            (comp/render-texture-region anim-frame effect-solar-texture 0 0 0 0 0.0 v0 1.0 v1))
          (catch Exception _e
            nil))))))

(defn create-solar-gui
  "Create Solar Generator GUI root widget."
  [container _player & [opts]]
  (try
    (let [inv-page (tech-ui/create-rework-page "guis/rework/page_solar.xml")
          inv-window (:window inv-page)
          wireless-window (wireless-tab/create-wireless-panel {:role :generator :container container})
          wireless-page {:id "wireless" :window wireless-window}
          pages [inv-page wireless-page]
          max-e (fn [] (max 1.0 (double @(:max-energy container))))
          speed-str (fn []
                      (try
                        (format "%.2fIF/T" (double @(:gen-speed container)))
                        (catch Exception _ "0.00IF/T")))
          assembled (tech-ui/assemble-tech-ui-root
                      {:pages pages
                       :container container
                       :minecraft-container (:menu opts)
                       :bind! (fn [_]
                                (attach-anim-frame! inv-window container))
                       :build-info-area!
                       (fn [info-area]
                         (let [y (tech-ui/add-histogram
                                   info-area
                                   [(tech-ui/hist-buffer (fn [] (double @(:energy container)))
                                                         (max-e))]
                                   0)
                               y (tech-ui/add-sepline info-area "Info" y)
                               y (tech-ui/add-property info-area "gen_speed" speed-str y)]
                           (tech-ui/add-property info-area "status" (fn [] @(:status container)) y)))})
          main-widget (:root assembled)]

      (log/info "Created Solar Generator GUI (TechUI)")
      (if (:menu opts)
        {:root main-widget :current (:current assembled)}
        main-widget))
    (catch Exception e
      (log/error "Error creating Solar GUI:"(ex-message e))
      (throw e))))

(defn create-screen
  "Create CGuiScreenContainer for Solar GUI."
  [container minecraft-container player]
  (let [gui (create-solar-gui container player {:menu minecraft-container})
        root (if (map? gui) (:root gui) gui)
        current (when (map? gui) (:current gui))]
    (tech-ui/create-tech-screen-from-root root current minecraft-container)))

;; ============================================================================
;; GUI Registration
;; ============================================================================

(defn- solar-container? [container]
  (and (map? container)
       (contains? container :tile-entity)
       (contains? container :energy)
       (contains? container :status)))

(defonce-guard solar-gui-installed?)

(defn init-solar-gui!
  []
  (with-init-guard solar-gui-installed?
    (slot-schema/register-slot-schema!
      {:schema-id solar-gen-id
       :slots [{:id :energy :type :energy :x 42 :y 81}]})
    (gui-reg/register-block-gui!
      (gui-manifest/gui-name :solar-gen)
      (merge (gui-manifest/gui-registration :solar-gen)
         {:container-predicate solar-container?
       :container-fn create-container
       :screen-fn create-screen
       :tick-fn tick!
       :sync-get get-sync-data
       :sync-apply apply-sync-data!
       :validate-fn still-valid?
       :close-fn on-close
       :button-click-fn handle-button-click!
       :slot-count-fn get-slot-count
       :slot-get-fn get-slot-item
       :slot-set-fn set-slot-item!
       :slot-can-place-fn can-place-item?
        :slot-changed-fn slot-changed!}))))

