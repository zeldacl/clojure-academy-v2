(ns cn.li.ac.wireless.gui.node-gui
  "Wireless Node GUI - Facade for node-gui-xml
  
  This module re-exports the XML/TechUI-based implementation from node-gui-xml.
  Kept for backward compatibility with existing references in registry.clj 
  and screen_factory.clj.
  
  The actual implementation is in node-gui-xml.clj."
  (:require [cn.li.ac.wireless.gui.node-gui-xml :as impl]))

;; ============================================================================
;; Re-exported Public API
;; ============================================================================

(def create-node-gui
  "Create Wireless Node GUI (delegates to node-gui-xml)"
  impl/create-node-gui)

(def create-screen
  "Create CGuiScreenContainer for Node GUI (delegates to node-gui-xml)"
  impl/create-screen)

(def open-node-gui
  "Open Wireless Node GUI for player (delegates to node-gui-xml)"
  impl/open-node-gui)

;; Re-export init function if exists
(when (resolve 'impl/init!)
  (def init! impl/init!))
