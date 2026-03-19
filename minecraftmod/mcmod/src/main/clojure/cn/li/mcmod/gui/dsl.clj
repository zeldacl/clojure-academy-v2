(ns cn.li.mcmod.gui.dsl
  "GUI DSL - Declarative GUI definition using Clojure macros
  
  Supports both:
  - legacy \"generic\" GUI specs (string :id, slots/buttons/labels, XML layout)
  - wireless GUI metadata + runtime hooks (int :gui-id, registry/screen/container/sync metadata)"
  (:require [clojure.string :as str]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.platform.item :as item]))

;; GUI Registry - stores all defined GUIs
(defonce ^{:doc "Registry for GUI specs.

Structure:
- :by-id       {string-id -> GuiSpec}
- :by-gui-id   {int-gui-id -> GuiSpec} (wireless/platform-visible GUIs only)"}
  gui-registry
  (atom {:by-id {} :by-gui-id {}}))

;; Component specifications
(defrecord SlotSpec [index x y filter on-change])
(defrecord ButtonSpec [id x y width height text on-click])
(defrecord LabelSpec [x y text color])
(defrecord GuiSpec
  [;; Legacy/generic ID used by XML/gui.container system (string)
   id

   ;; Wireless/platform-visible GUI ID (int). When present, platform layers
   ;; can register MenuType/ScreenHandlerType and open GUIs using this id.
   gui-id

   ;; Metadata used by platform adapters (wireless GUIs)
   display-name
   gui-type
   registry-name
   screen-factory-fn-kw
   slot-layout

   ;; Runtime hooks for wireless GUIs
   container-fn
   container-predicate
   screen-fn
   tick-fn
   sync-get
   sync-apply
   payload-sync-apply-fn

   ;; Legacy/generic GUI layout fields
   title width height slots buttons labels background])

;; Default values
(def default-gui-width 176)
(def default-gui-height 166)
(def default-button-width 60)
(def default-button-height 20)

;; Slot filter predicates
(defn any-item-filter [_item] true)

(defn item-type-filter [item-type]
  (fn [item-stack]
    (= (item/item-get-item item-stack) item-type)))

;; Parse slot specification
(defn parse-slot [slot-map]
  (map->SlotSpec
    {:index (:index slot-map)
     :x (:x slot-map 0)
     :y (:y slot-map 0)
     :filter (or (:filter slot-map) any-item-filter)
     :on-change (or (:on-change slot-map) (fn [_ _] nil))}))

;; Parse button specification
(defn parse-button [button-map]
  (map->ButtonSpec
    {:id (:id button-map)
     :x (:x button-map 0)
     :y (:y button-map 0)
     :width (or (:width button-map) default-button-width)
     :height (or (:height button-map) default-button-height)
     :text (:text button-map "Button")
     :on-click (or (:on-click button-map) (fn [] (log/info "Button clicked")))}))

;; Parse label specification
(defn parse-label [label-map]
  (map->LabelSpec
    {:x (:x label-map 0)
     :y (:y label-map 0)
     :text (:text label-map "")
     :color (or (:color label-map) 0x404040)}))

;; Validate GUI specification
(defn validate-gui-spec [gui-spec]
  (when-not (and (:id gui-spec) (string? (:id gui-spec)) (not (str/blank? (:id gui-spec))))
    (throw (ex-info "GUI must have a non-empty string :id" {:id (:id gui-spec) :spec gui-spec})))

  ;; If this GUI participates in platform registration, require wireless metadata.
  (when (some? (:gui-id gui-spec))
    (when-not (integer? (:gui-id gui-spec))
      (throw (ex-info "GUI :gui-id must be an integer when provided" {:gui-id (:gui-id gui-spec) :id (:id gui-spec)})))
    (when-not (and (string? (:registry-name gui-spec)) (not (str/blank? (:registry-name gui-spec))))
      (throw (ex-info "GUI :registry-name must be a non-empty string when :gui-id is present"
                      {:id (:id gui-spec) :gui-id (:gui-id gui-spec) :registry-name (:registry-name gui-spec)})))
    (when-not (keyword? (:gui-type gui-spec))
      (throw (ex-info "GUI :gui-type must be a keyword when :gui-id is present"
                      {:id (:id gui-spec) :gui-id (:gui-id gui-spec) :gui-type (:gui-type gui-spec)})))
    (when-not (keyword? (:screen-factory-fn-kw gui-spec))
      (throw (ex-info "GUI :screen-factory-fn-kw must be a keyword when :gui-id is present"
                      {:id (:id gui-spec) :gui-id (:gui-id gui-spec) :screen-factory-fn-kw (:screen-factory-fn-kw gui-spec)}))))

  (doseq [slot (:slots gui-spec)]
    (when-not (number? (:index slot))
      (throw (ex-info "Slot must have an :index number" {:slot slot}))))
  (doseq [button (:buttons gui-spec)]
    (when-not (number? (:id button))
      (throw (ex-info "Button must have an :id number" {:button button}))))
  true)

;; Create GUI specification from options
(defn create-gui-spec [gui-id options]
  (let [slots (mapv parse-slot (or (:slots options) []))
        buttons (mapv parse-button (or (:buttons options) []))
        labels (mapv parse-label (or (:labels options) []))
        spec (map->GuiSpec
               {:id gui-id
                ;; Wireless metadata (optional)
                :gui-id (:gui-id options)
                :display-name (:display-name options)
                :gui-type (:gui-type options)
                :registry-name (:registry-name options)
                :screen-factory-fn-kw (:screen-factory-fn-kw options)
                :slot-layout (:slot-layout options)
                ;; Wireless runtime hooks (optional)
                :container-fn (:container-fn options)
                :container-predicate (:container-predicate options)
                :screen-fn (:screen-fn options)
                :tick-fn (:tick-fn options)
                :sync-get (:sync-get options)
                :sync-apply (:sync-apply options)
                :payload-sync-apply-fn (:payload-sync-apply-fn options)
                ;; Legacy/generic fields
                :title (or (:title options) "GUI")
                :width (or (:width options) default-gui-width)
                :height (or (:height options) default-gui-height)
                :slots slots
                :buttons buttons
                :labels labels
                :background (or (:background options) :default)})]
    (validate-gui-spec spec)
    spec))

;; Register GUI in registry
(defn register-gui! [gui-spec]
  (log/info "Registering GUI:" (:id gui-spec))
  (swap! gui-registry
         (fn [reg]
           (let [id (:id gui-spec)
                 gui-id (:gui-id gui-spec)]
             (when (and (some? gui-id) (contains? (:by-gui-id reg) gui-id))
               (throw (ex-info "Duplicate :gui-id registered"
                               {:gui-id gui-id
                                :existing-id (get-in reg [:by-gui-id gui-id :id])
                                :new-id id})))
             (cond-> reg
               true (assoc-in [:by-id id] gui-spec)
               (some? gui-id) (assoc-in [:by-gui-id gui-id] gui-spec)))))
  gui-spec)

;; Get GUI from registry
(defn get-gui [gui-id]
  (get-in @gui-registry [:by-id gui-id]))

;; List all registered GUIs
(defn list-guis []
  (keys (:by-id @gui-registry)))

;; ============================================================================
;; Wireless/platform-visible GUI query API (int gui-id)
;; ============================================================================

(defn get-gui-by-gui-id
  "Get a GUI spec by wireless/platform GUI id (int)."
  [gui-id]
  (get-in @gui-registry [:by-gui-id gui-id]))

(defn list-gui-ids
  "List all registered wireless/platform GUI ids (ints)."
  []
  (keys (:by-gui-id @gui-registry)))

(defn get-all-gui-ids
  "Alias for platform adapters."
  []
  (seq (list-gui-ids)))

(defn get-registry-name
  "Get registry name for a wireless GUI by gui-id."
  [gui-id]
  (some-> (get-gui-by-gui-id gui-id) :registry-name))

(defn get-screen-factory-fn-kw
  "Get screen factory keyword for a wireless GUI by gui-id."
  [gui-id]
  (some-> (get-gui-by-gui-id gui-id) :screen-factory-fn-kw))

(defn get-gui-type
  "Get container/gui type keyword for a wireless GUI by gui-id."
  [gui-id]
  (some-> (get-gui-by-gui-id gui-id) :gui-type))

(defn get-slot-layout
  "Get slot layout for a wireless GUI by gui-id."
  [gui-id]
  (some-> (get-gui-by-gui-id gui-id) :slot-layout))

(defn get-slot-range
  "Get slot index range for a wireless GUI section.

  Returns [start end] inclusive, or [0 0] when not found."
  [gui-id section]
  (if-let [layout (get-slot-layout gui-id)]
    (get-in layout [:ranges section] [0 0])
    [0 0]))

(defn get-gui-by-type
  "Get a registered wireless GUI spec by its :gui-type keyword."
  [gui-type]
  (some (fn [[_gui-id spec]]
          (when (= (:gui-type spec) gui-type)
            spec))
        (:by-gui-id @gui-registry)))

(defn get-gui-id-for-type
  "Get GUI id (int) for a :gui-type keyword, or nil."
  [gui-type]
  (some (fn [[gui-id spec]]
          (when (= (:gui-type spec) gui-type)
            gui-id))
        (:by-gui-id @gui-registry)))

;; Main macro: defgui
(defmacro defgui
  "Define a GUI with declarative syntax
  
  Example:
  (defgui my-gui
    :title \"My GUI\"
    :width 176
    :height 166
    :slots [{:index 0 :x 80 :y 35}]
    :buttons [{:id 0 :x 120 :y 30 :text \"OK\" :on-click #(println \"Clicked!\")}]
    :labels [{:x 8 :y 6 :text \"Inventory\"}])"
  [gui-name & options]
  (let [gui-id (name gui-name)
        options-map (apply hash-map options)]
    `(def ~gui-name
       (register-gui!
         (create-gui-spec ~gui-id ~options-map)))))

;; XML-based GUI macro
(defmacro defgui-from-xml
  "Define a GUI from XML layout file
  
  Example:
  (defgui-from-xml node-gui
    :xml-layout \"page_wireless_node\"
    :on-init (fn [gui] ...)
    :on-render (fn [gui dt] ...))"
  [gui-name & options]
  (let [options-map (apply hash-map options)
        xml-layout (:xml-layout options-map)
        gui-id (name gui-name)]
    `(def ~gui-name
       (let [xml-parser# (requiring-resolve 'cn.li.mcmod.gui.xml-parser/load-gui-from-xml)
         base-spec# (xml-parser# ~gui-id ~xml-layout)
         merged-spec# (merge base-spec# ~(dissoc options-map :xml-layout))]
       (register-gui! (create-gui-spec (:id base-spec#) merged-spec#))))))

;; Helper: create slot handler that updates atom
(defn slot-change-handler [slots-atom slot-index]
  (fn [old-stack new-stack]
    (log/info "Slot" slot-index "changed from" old-stack "to" new-stack)
    (swap! slots-atom assoc slot-index new-stack)))

;; Helper: create button handler that clears slot
(defn clear-slot-handler [slots-atom slot-index]
  (fn []
    (log/info "Clearing slot" slot-index)
    (swap! slots-atom dissoc slot-index)))

;; Helper: create button handler that validates and processes
(defn processing-handler [slots-atom input-slots output-slot process-fn]
  (fn []
    (let [inputs (mapv #(get @slots-atom %) input-slots)]
      (when (every? some? inputs)
        (try
          (let [result (apply process-fn inputs)]
            (log/info "Processing successful, result:" result)
            (doseq [slot input-slots]
              (swap! slots-atom dissoc slot))
            (swap! slots-atom assoc output-slot result))
          (catch Exception e
            (log/info "Processing failed:" (.getMessage e))))))))

;; GUI instance management
(defrecord GuiInstance [spec player world pos data])

(defn create-gui-instance
  "Create a runtime instance of a GUI for a specific player"
  [gui-spec player world pos]
  (let [slots-atom (atom {})
        buttons-with-state (mapv
                             (fn [btn]
                               (assoc btn :enabled (atom true)))
                             (:buttons gui-spec))]
    (map->GuiInstance
      {:spec gui-spec
       :player player
       :world world
       :pos pos
       :data {:slots slots-atom
              :buttons buttons-with-state}})))

;; Get slot from instance
(defn get-slot-state [gui-instance slot-index]
  (get @(get-in gui-instance [:data :slots]) slot-index))

;; Set slot in instance
(defn set-slot-state! [gui-instance slot-index item-stack]
  (swap! (get-in gui-instance [:data :slots]) assoc slot-index item-stack))

;; Clear slot in instance
(defn clear-slot-state! [gui-instance slot-index]
  (swap! (get-in gui-instance [:data :slots]) dissoc slot-index))

;; Get button state
(defn button-enabled? [gui-instance button-id]
  (let [button (nth (get-in gui-instance [:data :buttons]) button-id nil)]
    (when button
      @(:enabled button))))

;; Set button enabled state
(defn set-button-enabled! [gui-instance button-id enabled?]
  (let [button (nth (get-in gui-instance [:data :buttons]) button-id nil)]
    (when button
      (reset! (:enabled button) enabled?))))

;; Execute button click
(defn handle-button-click [gui-instance button-id]
  (let [button-spec (nth (:buttons (:spec gui-instance)) button-id nil)]
    (when (and button-spec (button-enabled? gui-instance button-id))
      (log/info "Executing button" button-id ":" (:text button-spec))
      ((:on-click button-spec)))))

;; Execute slot change
(defn handle-slot-change [gui-instance slot-index old-stack new-stack]
  (let [slot-spec (nth (:slots (:spec gui-instance)) slot-index nil)]
    (when slot-spec
      (if ((:filter slot-spec) new-stack)
        (do
          (set-slot-state! gui-instance slot-index new-stack)
          ((:on-change slot-spec) old-stack new-stack)
          true)
        (do
          (log/info "Item rejected by slot filter")
          false)))))
