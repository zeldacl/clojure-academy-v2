(ns cn.li.mcmod.gui.dsl
  "GUI DSL - Declarative GUI definitions and runtime helpers.

  Runtime metadata queries live in cn.li.mcmod.gui.registry."
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.gui.parser :as gui-parser]
            [cn.li.mcmod.gui.validator :as gui-validator]
            [cn.li.mcmod.gui.registry :as gui-registry]))

(def default-gui-width gui-parser/default-gui-width)
(def default-gui-height gui-parser/default-gui-height)
(def default-button-width gui-parser/default-button-width)
(def default-button-height gui-parser/default-button-height)

(def any-item-filter gui-parser/any-item-filter)
(def parse-slot gui-parser/parse-slot)
(def parse-button gui-parser/parse-button)
(def parse-label gui-parser/parse-label)
(def validate-gui-spec gui-validator/validate-gui-spec)
(def create-gui-spec gui-parser/create-gui-spec)
(def register-gui! gui-registry/register-gui!)

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
            (log/info "Processing failed:"(ex-message e))))))))

;; GUI instance management
(defrecord GuiInstance [spec player world pos data])

(defn create-gui-instance
  "Create a runtime instance of a GUI for a specific player"
  [gui-spec player world pos]
  (let [slots-atom (atom {})
        layout (:layout gui-spec)
        buttons-with-state (mapv
                             (fn [btn]
                               (assoc btn :enabled (atom true)))
                             (:buttons layout))]
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
  (let [layout (get-in gui-instance [:spec :layout])
        button-spec (nth (:buttons layout) button-id nil)]
    (when (and button-spec (button-enabled? gui-instance button-id))
      (log/info "Executing button" button-id ":" (:text button-spec))
      ((:on-click button-spec)))))

;; Execute slot change
(defn handle-slot-change [gui-instance slot-index old-stack new-stack]
  (let [layout (get-in gui-instance [:spec :layout])
        slot-spec (nth (:slots layout) slot-index nil)]
    (when slot-spec
      (if ((:filter slot-spec) new-stack)
        (do
          (set-slot-state! gui-instance slot-index new-stack)
          ((:on-change slot-spec) old-stack new-stack)
          true)
        (do
          (log/info "Item rejected by slot filter")
          false)))))

