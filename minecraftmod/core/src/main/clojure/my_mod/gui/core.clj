(ns my-mod.gui.core
  "Core GUI logic - button handlers and slot management"
  (:require [my-mod.util.log :as log]))

;; GUI state atom (per-player, per-GUI instance in practice)
(defonce gui-slots (atom {}))

(defn register-gui-slot
  "Register a GUI slot with initial item"
  [gui-id slot-index item-stack]
  (swap! gui-slots assoc-in [gui-id slot-index] item-stack)
  (log/info "Registered GUI slot" gui-id slot-index "with item:" item-stack))

(defn get-slot-item
  "Get item from a specific slot"
  [gui-id slot-index]
  (get-in @gui-slots [gui-id slot-index]))

(defn clear-slot
  "Clear a specific slot (destroy item)"
  [gui-id slot-index]
  (log/info "Clearing slot" slot-index "in GUI" gui-id)
  (swap! gui-slots assoc-in [gui-id slot-index] nil)
  {:action :cleared
   :gui-id gui-id
   :slot slot-index})

(defn on-destroy-button-clicked
  "Handler when destroy button is clicked - removes item from slot"
  [gui-id slot-index]
  (log/info "Destroy button clicked for GUI" gui-id "slot" slot-index)
  (clear-slot gui-id slot-index))

(defn get-gui-title
  "Return the GUI window title"
  []
  "Demo GUI")

(defn validate-gui-open
  "Check if GUI can be opened (e.g., right block, permissions)"
  [player world pos block]
  (log/info "Validating GUI open for player at" pos)
  true)
