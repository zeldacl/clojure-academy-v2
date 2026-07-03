(ns cn.li.mcmod.platform.resource
  "Platform-agnostic resource identifier abstraction via Framework function map.

   Resource factory stored at [:platform :resource :factory].
   Resource location fn stored at [:platform :resource :location-fn]."
  (:require [cn.li.mcmod.framework :as fw]))

;; ============================================================================
;; Resource factory — creates platform-specific resource identifiers
;; ============================================================================

(defn install-resource-factory!
  [factory-fn _label]
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :resource :factory] factory-fn)) nil)

(defn call-with-resource-factory [factory-fn f] (f factory-fn))

(defn create-resource-location
  "Create a platform-specific resource identifier."
  [namespace path]
  (if-let [factory (get-in @(fw/fw-atom) [:platform :resource :factory])]
    (factory namespace path)
    (throw (ex-info "Resource factory not initialized"
                    {:hint "Platform must call install-resource-factory! first"}))))

(defn factory-initialized?
  []
  (some? (get-in @(fw/fw-atom) [:platform :resource :factory])))

;; ============================================================================
;; High-level resource location fn (optional, set by ac/loader)
;; ============================================================================

(defn install-resource-location-fn!
  [location-fn _label]
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :resource :location-fn] location-fn)) nil)

(defn call-with-resource-location-fn [location-fn f] (f location-fn))

(def ^:private default-resource-namespace
  (or (System/getenv "MOD_ID") "my_mod"))

(defn invoke-resource-location
  "Call the injected resource-location function."
  ([path]
   (if-let [f (get-in @(fw/fw-atom) [:platform :resource :location-fn])]
     (f nil path)
     (create-resource-location default-resource-namespace path)))
  ([namespace path]
   (if-let [f (get-in @(fw/fw-atom) [:platform :resource :location-fn])]
     (f namespace path)
     (create-resource-location (or namespace default-resource-namespace) path))))
