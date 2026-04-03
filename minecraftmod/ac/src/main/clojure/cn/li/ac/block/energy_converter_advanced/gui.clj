(ns cn.li.ac.block.energy-converter-advanced.gui
  "CLIENT-ONLY: Advanced Energy Converter GUI implementation.

  Reuses the basic converter GUI components with advanced tier configuration."
  (:require [cn.li.mcmod.gui.cgui :as cgui]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.gui.dsl :as gui-dsl]
            [cn.li.ac.gui.tech-ui-common :as tech-ui]
            [cn.li.ac.block.energy-converter.gui :as base-gui]
            [cn.li.ac.block.energy-converter-advanced.config :as converter-config]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.ac.wireless.gui.container.schema-runtime :as schema-runtime]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.block.energy-converter.schema :as converter-schema]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Slot Schema (reuse basic converter schema)
;; ============================================================================

(def energy-converter-advanced-id :energy-converter-advanced)

(def energy-converter-advanced-slot-schema
  (slot-schema/register-slot-schema!
    {:schema-id energy-converter-advanced-id
     :slots [{:id :input :type :item :x 56 :y 35}
             {:id :output :type :item :x 56 :y 71}]}))

;; ============================================================================
;; Message Registration
;; ============================================================================

(msg-registry/register-block-messages!
  :energy-converter-advanced
  [:get-status :set-mode])

(def gui-width tech-ui/gui-width)
(def gui-height tech-ui/gui-height)

;; ============================================================================
;; Container Creation (reuse basic converter logic)
;; ============================================================================

(def ^:private converter-slot-schema-id energy-converter-advanced-id)

(defn create-container [tile player]
  (let [state (or (common/get-tile-state tile) {})]
    (merge {:tile-entity    tile
            :player         player
            :container-type :energy-converter-advanced}
           (schema-runtime/build-gui-atoms converter-schema/unified-converter-schema state))))

(defn get-slot-count [_container]
  (slot-schema/get-slot-count converter-slot-schema-id))

(def can-place-item? base-gui/can-place-item?)
(def get-slot-item base-gui/get-slot-item)
(def set-slot-item! base-gui/set-slot-item!)
(def slot-changed! base-gui/slot-changed!)
(def still-valid? base-gui/still-valid?)

(defn sync-to-client! [container]
  (let [state (or (common/get-tile-state (:tile-entity container)) {})
        new-energy (double (get state :energy 0.0))
        new-max-energy (double (get state :max-energy (converter-config/max-energy)))
        new-mode (str (get state :mode "charge-items"))
        new-transfer-rate (double (get state :transfer-rate 0.0))]
    (when (not= new-energy @(:energy container))
      (reset! (:energy container) new-energy))
    (when (not= new-max-energy @(:max-energy container))
      (reset! (:max-energy container) new-max-energy))
    (when (not= new-mode @(:mode container))
      (reset! (:mode container) new-mode))
    (when (not= new-transfer-rate @(:transfer-rate container))
      (reset! (:transfer-rate container) new-transfer-rate))))

(def get-sync-data base-gui/get-sync-data)
(def apply-sync-data! base-gui/apply-sync-data!)

(defn tick! [container]
  (swap! (:sync-ticker container) inc)
  (sync-to-client! container))

(def handle-button-click! base-gui/handle-button-click!)
(def on-close base-gui/on-close)

;; ============================================================================
;; GUI Components (reuse with different title)
;; ============================================================================

(defn create-converter-gui
  "Create Advanced Energy Converter GUI root widget."
  [container _player & [opts]]
  (try
    (let [inv-window (cgui/create-widget :window {:width gui-width :height gui-height})
          title (cgui/create-widget :text {:text "Advanced Energy Converter" :x 8 :y 6})
          _ (cgui/add-widget! inv-window title)

          energy-bar (cgui/create-widget :progress-bar
                       {:x 20 :y 30 :width 100 :height 10
                        :progress (fn []
                                    (let [e (double @(:energy container))
                                          m (max 1.0 (double @(:max-energy container)))]
                                      (/ e m)))})
          _ (cgui/add-widget! inv-window energy-bar)

          energy-text (cgui/create-widget :text
                        {:x 20 :y 45
                         :text (fn []
                                 (format "%.0f / %.0f IF"
                                         (double @(:energy container))
                                         (double @(:max-energy container))))})
          _ (cgui/add-widget! inv-window energy-text)

          mode-text (cgui/create-widget :text
                      {:x 20 :y 60
                       :text (fn [] (str "Mode: " @(:mode container)))})
          _ (cgui/add-widget! inv-window mode-text)

          rate-text (cgui/create-widget :text
                      {:x 20 :y 75
                       :text (fn [] (format "Transfer: %.1f IF/T" (double @(:transfer-rate container))))})
          _ (cgui/add-widget! inv-window rate-text)

          conv-text (cgui/create-widget :text
                      {:x 20 :y 90
                       :text (fn [] (format "1 IF = %.1f FE" (double @(:conversion-rate container))))})
          _ (cgui/add-widget! inv-window conv-text)]

      (log/info "Created Advanced Energy Converter GUI")
      inv-window)
    (catch Exception e
      (log/error "Error creating Advanced Energy Converter GUI:" (ex-message e))
      (throw e))))

(defn create-screen
  "Create CGuiScreenContainer for Advanced Energy Converter GUI."
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
       (contains? container :mode)
       (= :energy-converter-advanced (:container-type container))))

(def ^:private converter-slot-layout
  (slot-schema/get-slot-layout energy-converter-advanced-id))

(gui-dsl/defgui-with-lazy-fns energy-converter-advanced
  :gui-id 11
  :namespace 'cn.li.ac.block.energy-converter-advanced.gui
  :display-name "Advanced Energy Converter"
  :gui-type :energy-converter-advanced
  :registry-name "energy_converter_advanced_gui"
  :screen-factory-fn-kw :create-converter-screen
  :slot-layout converter-slot-layout
  :container-predicate converter-container?)
