(ns cn.li.ac.block.energy-converter.gui
  "CLIENT-ONLY: Energy Converter GUI implementation.

  This file contains:
  - GUI layout and component builders
  - GUI interaction logic
  - Container atom management
  - Message registration and network handlers

  Must be loaded via side-checked requiring-resolve from platform layer."
  (:require [cn.li.mcmod.gui.cgui :as cgui]
            [cn.li.mcmod.gui.xml-parser :as cgui-doc]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.ac.gui.platform-adapter :as gui]
            [cn.li.mcmod.gui.tabbed-gui :as tabbed-gui]
            [cn.li.ac.gui.tech-ui-common :as tech-ui]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.gui.slot-registry :as slot-registry]
            [cn.li.mcmod.gui.dsl :as gui-dsl]
            [cn.li.ac.energy.operations :as energy-ops]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.ac.wireless.gui.container.schema-runtime :as schema-runtime]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.block.energy-converter.schema :as converter-schema]
            [cn.li.ac.block.energy-converter.config :as converter-config]
            [cn.li.ac.registry.hooks :as hooks]))

;; ============================================================================
;; Slot Schema
;; ============================================================================

(def energy-converter-id :energy-converter)

(def gui-width tech-ui/gui-width)
(def gui-height tech-ui/gui-height)

;; ============================================================================
;; Container Creation
;; ============================================================================

(def ^:private converter-slot-schema-id energy-converter-id)

(defn create-container [tile player]
  (let [state (or (common/get-tile-state tile) {})]
    (merge {:tile-entity    tile
            :player         player
            :container-type :energy-converter}
           (schema-runtime/build-gui-atoms converter-schema/unified-converter-schema state))))

(defn get-slot-count [_container]
  (slot-registry/get-slot-count converter-slot-schema-id))

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
        new-max-energy (double (get state :max-energy (converter-config/max-energy)))
        new-mode (str (get state :mode "charge-items"))
        new-transfer-rate (double (get state :transfer-rate 0.0))]
    ;; Only update atoms if values changed
    (when (not= new-energy @(:energy container))
      (reset! (:energy container) new-energy))
    (when (not= new-max-energy @(:max-energy container))
      (reset! (:max-energy container) new-max-energy))
    (when (not= new-mode @(:mode container))
      (reset! (:mode container) new-mode))
    (when (not= new-transfer-rate @(:transfer-rate container))
      (reset! (:transfer-rate container) new-transfer-rate))))

(defn get-sync-data [container]
  ((schema-runtime/build-get-sync-data-fn converter-schema/unified-converter-schema) container))

(defn apply-sync-data! [container data]
  ((schema-runtime/build-apply-sync-data-fn converter-schema/unified-converter-schema) container data))

(defn tick! [container]
  (swap! (:sync-ticker container) inc)
  (sync-to-client! container))

(defn handle-button-click! [_container _button-id _player] nil)

(defn on-close [container]
  ((schema-runtime/build-on-close-fn converter-schema/unified-converter-schema) container))

;; ============================================================================
;; GUI Components
;; ============================================================================

(defn create-converter-gui
  "Create Energy Converter GUI root widget."
  [container _player & [opts]]
  (try
    (let [;; Create simple GUI window
          inv-window (cgui/create-widget :window {:width gui-width :height gui-height})

          ;; Add title
          title (cgui/create-widget :text {:text "Energy Converter" :x 8 :y 6})
          _ (cgui/add-widget! inv-window title)

          ;; Add energy bar
          energy-bar (cgui/create-widget :progress-bar
                       {:x 20 :y 30 :width 100 :height 10
                        :progress (fn []
                                    (let [e (double @(:energy container))
                                          m (max 1.0 (double @(:max-energy container)))]
                                      (/ e m)))})
          _ (cgui/add-widget! inv-window energy-bar)

          ;; Add energy text
          energy-text (cgui/create-widget :text
                        {:x 20 :y 45
                         :text (fn []
                                 (format "%.0f / %.0f IF"
                                         (double @(:energy container))
                                         (double @(:max-energy container))))})
          _ (cgui/add-widget! inv-window energy-text)

          ;; Add mode text
          mode-text (cgui/create-widget :text
                      {:x 20 :y 60
                       :text (fn [] (str "Mode: " @(:mode container)))})
          _ (cgui/add-widget! inv-window mode-text)

          ;; Add transfer rate text
          rate-text (cgui/create-widget :text
                      {:x 20 :y 75
                       :text (fn [] (format "Transfer: %.1f IF/T" (double @(:transfer-rate container))))})
          _ (cgui/add-widget! inv-window rate-text)

          ;; Add conversion rate text
          conv-text (cgui/create-widget :text
                      {:x 20 :y 90
                       :text (fn [] (format "1 IF = %.1f FE" (double @(:conversion-rate container))))})
          _ (cgui/add-widget! inv-window conv-text)]

      (log/info "Created Energy Converter GUI")
      inv-window)
    (catch Exception e
      (log/error "Error creating Energy Converter GUI:" (ex-message e))
      (throw e))))

(defn create-screen
  "Create CGuiScreenContainer for Energy Converter GUI."
  [container minecraft-container player]
  (let [gui (create-converter-gui container player {:menu minecraft-container})
        root (if (map? gui) (:root gui) gui)
        base (cgui/create-cgui-screen-container root minecraft-container)]
    (tech-ui/assoc-tech-ui-screen-size base)))

;; ============================================================================
;; GUI Registration
;; ============================================================================

(defn- converter-container? [container]
  (and (map? container)
       (contains? container :tile-entity)
       (contains? container :energy)
       (contains? container :mode)))

(defonce ^:private energy-converter-gui-installed? (atom false))

(defn init-energy-converter-gui!
  []
  (when (compare-and-set! energy-converter-gui-installed? false true)
    (slot-schema/register-slot-schema!
      {:schema-id energy-converter-id
       :slots [{:id :input :type :item :x 56 :y 35}
               {:id :output :type :item :x 56 :y 71}]})
    (msg-registry/register-block-messages! :energy-converter [:get-status :set-mode])
    (gui-dsl/register-gui!
      (gui-dsl/create-gui-spec
        "energy-converter"
        {:gui-id 10
         :display-name "Energy Converter"
         :gui-type :energy-converter
         :registry-name "energy_converter_gui"
         :screen-factory-fn-kw :create-converter-screen
         :slot-layout (slot-schema/get-slot-layout energy-converter-id)
         :container-predicate converter-container?
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
