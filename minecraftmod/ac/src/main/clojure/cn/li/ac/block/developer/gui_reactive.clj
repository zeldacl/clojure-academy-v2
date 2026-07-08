(ns cn.li.ac.block.developer.gui-reactive
  "Complete reactive replacement for developer/gui.clj screen rendering.
   Container/slot/sync/validation logic delegates to the old gui.clj's
   public defns (create-container, get-slot-count, etc.) — reused as-is to
   avoid behavior drift. Screen content (classic page_developer.xml layout,
   console, skill-tree area, cover overlays) lives in panel-reactive.clj."
  (:require [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.mcmod.gui.spec :as gui-reg]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.gui.manifest :as gui-manifest]
            [cn.li.ac.block.developer.panel-reactive :as panel-reactive]
            [cn.li.ac.block.developer.gui :as old-gui]))

(defn create-screen [container menu player]
  (panel-reactive/create-screen container menu player))

(defonce-guard developer-gui-reactive-installed?)

(defn init-developer-reactive!
  []
  (with-init-guard developer-gui-reactive-installed?
    (gui-reg/register-block-gui!
      (gui-manifest/gui-name :developer)
      (merge (gui-manifest/gui-registration :developer)
        {:container-predicate old-gui/developer-container?
         :container-fn old-gui/create-container
         :screen-fn create-screen
         :server-menu-sync-fn old-gui/server-menu-sync!
         :validate-fn old-gui/still-valid?
         :close-fn old-gui/on-close
         :button-click-fn old-gui/handle-button-click!
         :slot-count-fn old-gui/get-slot-count
         :slot-get-fn old-gui/get-slot-item
         :slot-set-fn old-gui/set-slot-item!
         :slot-can-place-fn old-gui/can-place-item?
         :slot-changed-fn old-gui/slot-changed!}))
    (log/info "Ability Developer GUI initialized (reactive: classic layout + console + skill-tree area)")))
