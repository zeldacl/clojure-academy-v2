(ns cn.li.mcmod.gui.adapter
  "Facade for unified GUI operations.

   This module is intended to be the single import point for platform GUI code.
   It provides:
   - screen-factory indirection via a registration atom
   - a unified GUI API by delegating remaining functions to the existing
    gameplay implementation (lazily, to avoid hard compile-time dependencies)."
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
;; Platform implementation injection (callback atom)
;; ============================================================================
;;
;; We want `mcmod` to be free from direct calls into the `ac` namespaces.
;; Platform/business code registers concrete function callbacks here during
;; content init. The unified GUI adapter below invokes them at runtime.

(defonce ^:private platform-impl
  ;; Map: keyword -> fn
  (atom nil))

(defn register-gui-platform-impl!
  "Register platform-specific GUI callbacks used by this unified adapter.

  `impl-map` must be a map from the keywords used by the wrapper functions
  in this namespace (e.g. `:set-client-container!`, `:slot-get-item`, ... )
  to function values."
  [impl-map]
  (when-not (map? impl-map)
    (throw (ex-info "Expected map for register-gui-platform-impl!"
                    {:impl-map-type (type impl-map)})))
  (reset! platform-impl impl-map)
  nil)

(defn- platform-impl-fn!
  [k]
  (let [m @platform-impl]
    (when-not m
      (throw (ex-info "GUI platform implementation not registered"
                      {:missing-key k
                       :hint "Call cn.li.mcmod.gui.adapter/register-gui-platform-impl! during content init."})))
    (when-not (contains? m k)
      (throw (ex-info "Missing GUI platform callback"
                      {:missing-key k
                       :available-keys (keys m)})))
    (get m k)))

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
(defn set-client-container! [container]
  ((platform-impl-fn! :set-client-container!) container))

(defn clear-client-container! []
  ((platform-impl-fn! :clear-client-container!)))

(defn get-client-container []
  ((platform-impl-fn! :get-client-container)))

(defn register-active-container! [container]
  ((platform-impl-fn! :register-active-container!) container))

(defn unregister-active-container! [container]
  ((platform-impl-fn! :unregister-active-container!) container))

(defn register-player-container! [player container]
  ((platform-impl-fn! :register-player-container!) player container))

(defn unregister-player-container! [player]
  ((platform-impl-fn! :unregister-player-container!) player))

(defn get-player-container [player]
  ((platform-impl-fn! :get-player-container) player))

(defn get-player-container-from-active [player]
  ((platform-impl-fn! :get-player-container-from-active) player))

(defn get-container-for-menu [menu]
  ((platform-impl-fn! :get-container-for-menu) menu))

(defn get-container-by-id [container-id]
  ((platform-impl-fn! :get-container-by-id) container-id))

(defn get-menu-container-id [menu]
  ((platform-impl-fn! :get-menu-container-id) menu))

(defn register-menu-container! [menu container]
  ((platform-impl-fn! :register-menu-container!) menu container))

(defn unregister-menu-container! [menu]
  ((platform-impl-fn! :unregister-menu-container!) menu))

(defn register-container-by-id! [container-id container]
  ((platform-impl-fn! :register-container-by-id!) container-id container))

(defn unregister-container-by-id! [container-id]
  ((platform-impl-fn! :unregister-container-by-id!) container-id))

;; Sync helpers used by menu bridges / screens.
(defn safe-tick! [container]
  ((platform-impl-fn! :safe-tick!) container))

(defn safe-validate [container player]
  ((platform-impl-fn! :safe-validate) container player))

(defn safe-sync! [container]
  ((platform-impl-fn! :safe-sync!) container))

(defn safe-close! [container]
  ((platform-impl-fn! :safe-close!) container))

(defn slot-count [container]
  ((platform-impl-fn! :slot-count) container))

(defn slot-get-item [container idx]
  ((platform-impl-fn! :slot-get-item) container idx))

(defn slot-set-item! [container idx item]
  ((platform-impl-fn! :slot-set-item!) container idx item))

(defn slot-changed! [container idx]
  ((platform-impl-fn! :slot-changed!) container idx))

(defn slot-can-place? [container idx stack]
  ((platform-impl-fn! :slot-can-place?) container idx stack))

(defn get-container-type [container]
  ((platform-impl-fn! :get-container-type) container))

(defn node-container? [container]
  ((platform-impl-fn! :node-container?) container))

(defn matrix-container? [container]
  ((platform-impl-fn! :matrix-container?) container))

;; Business-layer routing helpers.
(defn get-gui-id-for-container [container]
  ((platform-impl-fn! :get-gui-id-for-container) container))

(defn get-menu-type
  "Get platform-specific MenuType for a GUI id."
  [platform gui-id]
  ((platform-impl-fn! :get-menu-type) platform gui-id))

;; ============================================================================
;; Platform MenuType registration (Forge/Fabric)
;; ============================================================================

(defn register-menu-type!
  "Register a platform-specific MenuType for a GUI id.

   This is called by platform loaders after creating the native MenuType/ScreenHandlerType.
   It routes into the business-layer metadata system via injected platform callbacks."
  [platform gui-id menu-type]
  ((platform-impl-fn! :register-menu-type!) platform gui-id menu-type))

(defn execute-quick-move-forge [menu container slot-index slot stack]
  ((platform-impl-fn! :execute-quick-move-forge) menu container slot-index slot stack))

;; ============================================================================
;; Set-tab support (used by mcmod/gui/tabbed-gui)
;; ============================================================================

(defn register-set-tab-handler!
  []
  (delegate 'cn.li.mcmod.gui.tabbed-gui/register-set-tab-handler!))

