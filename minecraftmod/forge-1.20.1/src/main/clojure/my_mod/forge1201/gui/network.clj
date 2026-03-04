(ns my-mod.forge1201.gui.network
  "Forge 1.20.1 GUI Network Packet System
  
  NOTE: Forge 1.20.1 networking API has changed significantly.
  This is currently a stub - custom packet handling will be reimplemented
  using the new Forge 1.20.1 networking API when needed.
  
  For now, GUI synchronization uses the built-in MenuType system."
  (:require [my-mod.util.log :as log]))

;; ============================================================================
;; Stub Implementation
;; ============================================================================

(defn init! 
  "Initialize network system (stub for Forge 1.20.1)
  
  TODO: Reimplement using Forge 1.20.1 networking API when custom packets are needed.
  Current GUI system uses MenuType which handles synchronization internally."
  []
  (log/info "Forge 1.20.1 GUI network system (stub) - using MenuType for synchronization"))
