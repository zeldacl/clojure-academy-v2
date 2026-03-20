(ns cn.li.ac.wireless.gui.matrix-gui
  "Wireless Matrix GUI - Facade for matrix-gui-xml
  
  This module re-exports the TechUI-based implementation from matrix-gui-xml.
  Kept for backward compatibility with existing references in registry.clj 
  and screen_factory.clj.
  
  The actual implementation is in matrix-gui-xml.clj which follows the 
  Scala TechUI.ContainerUI architecture."
  (:require [cn.li.ac.wireless.gui.matrix-gui-xml :as impl]))

;; ============================================================================
;; Re-exported Public API
;; ============================================================================

(def create-matrix-gui 
  "Create Wireless Matrix GUI (delegates to matrix-gui-xml)"
  impl/create-matrix-gui)

(def create-screen 
  "Create CGuiScreenContainer for Matrix GUI (delegates to matrix-gui-xml)"
  impl/create-screen)

(def open-matrix-gui
  "Open Wireless Matrix GUI for player (delegates to matrix-gui-xml)"
  impl/open-matrix-gui)

;; Re-export init function if exists
(when (resolve 'impl/init!)
  (def init! impl/init!))

