(ns cn.li.mcmod.gui.adapter
  "Facade replacing `cn.li.ac.gui.platform-adapter`.

   This module is intended to be the single import point for platform GUI code.
   It provides:
   - screen-factory indirection via a registration atom
   - a unified GUI API by delegating remaining functions to the existing
     wireless implementation (lazily, to avoid hard compile-time dependencies)."
  (:require [cn.li.mcmod.registry.metadata :as registry-metadata]
            [cn.li.mcmod.gui.slot-registry :as slot-registry]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.gui.handler :as gui-handler]))

(defonce ^:private resolved-vars
  (atom {}))

(defn- resolve-var
  "Resolve and cache a var by its full symbol: 'ns/var-name."
  [var-sym]
  (or (@resolved-vars var-sym)
      (let [v (requiring-resolve var-sym)]
        (swap! resolved-vars assoc var-sym v)
        v)))

(defn- delegate
  "Delegate a call to a resolved var without loading target namespaces at mcmod compile time."
  [var-sym & args]
  (apply (resolve-var var-sym) args))

;; ============================================================================
;; Screen factories (Group E)
;; ============================================================================

(defonce screen-factories
  ;; Map: screen-fn-kw -> (fn [menu player-inventory title] -> CGuiScreenContainer)
  (atom {}))

(defn register-screen-factory!
  "Register a screen factory function by keyword."
  [screen-fn-kw screen-fn]
  (swap! screen-factories assoc (keyword screen-fn-kw) screen-fn)
  nil)

(defn get-screen-factory-fn
  "Get registered screen factory fn by keyword.
   Throws when missing so issues surface early."
  [screen-fn-kw]
  (if-let [f (get @screen-factories (keyword screen-fn-kw))]
    f
    (throw (ex-info "Screen factory not registered"
                    {:screen-fn-kw screen-fn-kw})) ))

(defn get-screen-factory-fn-kw
  "Get screen factory function keyword for a GUI id."
  [gui-id]
  (registry-metadata/get-gui-screen-factory-fn-kw gui-id))

(defn create-node-screen
  "Adapter entrypoint used by platform client code."
  [menu player-inventory title]
  ((get-screen-factory-fn :create-node-screen) menu player-inventory title))

(defn create-matrix-screen
  "Adapter entrypoint used by platform client code."
  [menu player-inventory title]
  ((get-screen-factory-fn :create-matrix-screen) menu player-inventory title))

(defn create-solar-screen
  "Adapter entrypoint used by platform client code."
  [menu player-inventory title]
  ((get-screen-factory-fn :create-solar-screen) menu player-inventory title))

;; ============================================================================
;; Metadata queries (Group A)
;; ============================================================================

(defn get-all-gui-ids
  []
  (registry-metadata/get-all-gui-ids))

(defn get-registry-name
  [gui-id]
  (registry-metadata/get-gui-registry-name gui-id))

(defn get-slot-layout
  [gui-id]
  (registry-metadata/get-gui-slot-layout gui-id))

(defn get-slot-range
  [gui-id section]
  (registry-metadata/get-gui-slot-range gui-id section))

;; ============================================================================
;; Platform menu registration (Group D)
;; ============================================================================

(defmulti register-gui-handler
  "Platform adapters may extend this multimethod.

   Forge/Fabric implementations can hook into platform GUI initialization."
  (fn [platform-type] platform-type))

(defmethod register-gui-handler :default [_platform-type]
  nil)

(defn init-gui-handler!
  "Optional platform hook.
   Currently kept as a thin wrapper around the platform adapter multimethod."
  [platform-type]
  (register-gui-handler platform-type)
  gui-handler/get-gui-handler)

;; ============================================================================
;; Remaining GUI API (delegated, Group B/C)
;; ============================================================================

;; Handler for platform menu provider / container creation.
(defn get-gui-handler [] (gui-handler/get-gui-handler))

;; Container registry helpers used by tabbed GUIs + menu bridges.
(defn set-client-container! [container] (delegate 'cn.li.ac.gui.platform-adapter/set-client-container! container))
(defn clear-client-container! [] (delegate 'cn.li.ac.gui.platform-adapter/clear-client-container!))
(defn get-client-container [] (delegate 'cn.li.ac.gui.platform-adapter/get-client-container))

(defn register-active-container! [container] (delegate 'cn.li.ac.gui.platform-adapter/register-active-container! container))
(defn unregister-active-container! [container] (delegate 'cn.li.ac.gui.platform-adapter/unregister-active-container! container))

(defn register-player-container! [player container] (delegate 'cn.li.ac.gui.platform-adapter/register-player-container! player container))
(defn unregister-player-container! [player] (delegate 'cn.li.ac.gui.platform-adapter/unregister-player-container! player))

(defn get-player-container [player] (delegate 'cn.li.ac.gui.platform-adapter/get-player-container player))
(defn get-player-container-from-active [player] (delegate 'cn.li.ac.gui.platform-adapter/get-player-container-from-active player))

(defn get-container-for-menu [menu] (delegate 'cn.li.ac.gui.platform-adapter/get-container-for-menu menu))
(defn get-container-by-id [container-id] (delegate 'cn.li.ac.gui.platform-adapter/get-container-by-id container-id))
(defn get-menu-container-id [menu] (delegate 'cn.li.ac.gui.platform-adapter/get-menu-container-id menu))
(defn register-menu-container! [menu container] (delegate 'cn.li.ac.gui.platform-adapter/register-menu-container! menu container))
(defn unregister-menu-container! [menu] (delegate 'cn.li.ac.gui.platform-adapter/unregister-menu-container! menu))

(defn register-container-by-id! [container-id container] (delegate 'cn.li.ac.gui.platform-adapter/register-container-by-id! container-id container))
(defn unregister-container-by-id! [container-id] (delegate 'cn.li.ac.gui.platform-adapter/unregister-container-by-id! container-id))

;; Sync helpers used by menu bridges / screens.
(defn safe-tick! [container] (delegate 'cn.li.ac.gui.platform-adapter/safe-tick! container))
(defn safe-validate [container player] (delegate 'cn.li.ac.gui.platform-adapter/safe-validate container player))
(defn safe-sync! [container] (delegate 'cn.li.ac.gui.platform-adapter/safe-sync! container))

(defn safe-close! [container] (delegate 'cn.li.ac.gui.platform-adapter/safe-close! container))

(defn slot-count [container] (delegate 'cn.li.ac.gui.platform-adapter/slot-count container))
(defn slot-get-item [container idx] (delegate 'cn.li.ac.gui.platform-adapter/slot-get-item container idx))
(defn slot-set-item! [container idx item] (delegate 'cn.li.ac.gui.platform-adapter/slot-set-item! container idx item))
(defn slot-changed! [container idx] (delegate 'cn.li.ac.gui.platform-adapter/slot-changed! container idx))
(defn slot-can-place? [container idx stack] (delegate 'cn.li.ac.gui.platform-adapter/slot-can-place? container idx stack))

(defn get-container-type [container] (delegate 'cn.li.ac.gui.platform-adapter/get-container-type container))
(defn node-container? [container] (delegate 'cn.li.ac.gui.platform-adapter/node-container? container))
(defn matrix-container? [container] (delegate 'cn.li.ac.gui.platform-adapter/matrix-container? container))

;; Business-layer routing helpers.
(defn get-gui-id-for-container [container]
  (delegate 'cn.li.ac.gui.platform-adapter/get-gui-id-for-container container))

(defn get-menu-type
  "Get platform-specific MenuType for a GUI id."
  [platform gui-id]
  (delegate 'cn.li.ac.gui.platform-adapter/get-menu-type platform gui-id))

(defn execute-quick-move-forge [menu container slot-index slot stack]
  (delegate 'cn.li.ac.gui.platform-adapter/execute-quick-move-forge menu container slot-index slot stack))

;; ============================================================================
;; Set-tab support (used by mcmod/gui/tabbed-gui)
;; ============================================================================

(defn register-set-tab-handler!
  []
  (delegate 'cn.li.mcmod.gui.tabbed-gui/register-set-tab-handler!))

;; Networking payload helpers are still provided by the wireless implementation.
(defn make-matrix-sync-packet [source]
  (delegate 'cn.li.ac.gui.platform-adapter/make-matrix-sync-packet source))

(defn apply-matrix-sync-payload! [payload]
  (delegate 'cn.li.ac.gui.platform-adapter/apply-matrix-sync-payload! payload))

(defn make-node-sync-packet [source]
  (delegate 'cn.li.ac.gui.platform-adapter/make-node-sync-packet source))

(defn apply-node-sync-payload! [payload]
  (delegate 'cn.li.ac.gui.platform-adapter/apply-node-sync-payload! payload))

