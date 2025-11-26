(ns my-mod.gui.dsl
  "GUI DSL - Declarative GUI definition using Clojure macros
  
  Supports both inline DSL and XML-based layout definitions."
  (:require [my-mod.util.log :as log]))

;; GUI Registry - stores all defined GUIs
(defonce gui-registry (atom {}))

;; Component specifications
(defrecord SlotSpec [index x y filter on-change])
(defrecord ButtonSpec [id x y width height text on-click])
(defrecord LabelSpec [x y text color])
(defrecord GuiSpec [id title width height slots buttons labels background])

;; Default values
(def default-gui-width 176)
(def default-gui-height 166)
(def default-button-width 60)
(def default-button-height 20)

;; Slot filter predicates
(defn any-item-filter [_item] true)

(defn item-type-filter [item-type]
  (fn [item]
    (= (.getItem item) item-type)))

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
  (when-not (:id gui-spec)
    (throw (ex-info "GUI must have an :id" {:spec gui-spec})))
  (when-not (string? (:id gui-spec))
    (throw (ex-info "GUI :id must be a string" {:id (:id gui-spec)})))
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
  (swap! gui-registry assoc (:id gui-spec) gui-spec)
  gui-spec)

;; Get GUI from registry
(defn get-gui [gui-id]
  (get @gui-registry gui-id))

;; List all registered GUIs
(defn list-guis []
  (keys @gui-registry))

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
       (let [xml-parser# (requiring-resolve 'my-mod.gui.xml-parser/load-gui-from-xml)
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
