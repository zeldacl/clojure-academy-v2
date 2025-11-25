(ns my-mod.forge1201.gui.impl
  "Forge 1.20.1 GUI/Menu implementation"
  (:require [my-mod.gui.api :as gui-api]
            [my-mod.gui.core :as gui-core]
            [my-mod.util.log :as log]))

;; GUI ID constant
(def demo-gui-id 1)

(defmethod gui-api/open-gui :forge-1.20.1
  [player gui-id world pos]
  (log/info "Opening GUI" gui-id "for player at" pos "(1.20.1)")
  ;; In Forge 1.20.1, similar to 1.16.5 but with updated API
  {:gui-id gui-id 
   :pos pos
   :player player
   :world world
   :version :forge-1.20.1})

(defmethod gui-api/register-menu-type :forge-1.20.1
  [menu-id factory]
  (log/info "Registering menu type" menu-id "(1.20.1)")
  {:menu-id menu-id})

(defn on-button-clicked
  "Called from Java/container when button is clicked
   button-id: integer identifying which button
   container: the Menu/Container instance (Java object)
   Returns: action map for processing"
  [button-id container]
  (log/info "Button" button-id "clicked in 1.20.1 GUI")
  (case button-id
    0 (do
        (log/info "Destroy button clicked - clearing slot")
        (gui-core/on-destroy-button-clicked demo-gui-id 0)
        {:action :destroy-slot
         :slot-index 0
         :container container})
    (do
      (log/info "Unknown button" button-id)
      {:action :none})))

(defn create-menu-title
  "Create menu title for 1.20.1 (Component)"
  []
  (gui-core/get-gui-title))

(defn get-slot-count
  "Return number of slots in demo GUI"
  []
  1)

(defn on-slot-changed
  "Called when a slot's contents change"
  [slot-index item-stack]
  (log/info "Slot" slot-index "changed, item:" item-stack)
  (gui-core/register-gui-slot demo-gui-id slot-index item-stack))
