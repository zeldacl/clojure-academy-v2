(ns cn.li.mcmod.platform.ops-ui
  "UI/Client rendering abstractions.
  
  This namespace consolidates protocols for content-owned UI widget factories.
  
  Platform implementations provide platform-specific rendering backends."
  (:require
    [cn.li.mcmod.platform.ui]))

;; =============================================================================
;; Re-exports for consolidated access
;; =============================================================================

(def register-widget-factory! cn.li.mcmod.platform.ui/register-widget-factory!)
(def register-widget-factories! cn.li.mcmod.platform.ui/register-widget-factories!)
(def create-widget cn.li.mcmod.platform.ui/create-widget)
