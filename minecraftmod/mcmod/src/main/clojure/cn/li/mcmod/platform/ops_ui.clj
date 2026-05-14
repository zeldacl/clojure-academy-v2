(ns cn.li.mcmod.platform.ops-ui
  "UI/Client rendering abstractions.
  
  This namespace consolidates protocols for GUI rendering and terminal UI:
  - Terminal UI widget creation (register-terminal-ui-hooks!, create-terminal-gui)
  
  Platform implementations provide platform-specific rendering backends."
  (:require
    [cn.li.mcmod.platform.terminal-ui]))

;; =============================================================================
;; Re-exports for consolidated access
;; =============================================================================

(def register-terminal-ui-hooks! cn.li.mcmod.platform.terminal-ui/register-terminal-ui-hooks!)
(def create-terminal-gui cn.li.mcmod.platform.terminal-ui/create-terminal-gui)
