(ns cn.li.fabric1201.integration.jei-impl
  "Fabric-specific JEI integration implementation (stub).

  For Fabric, JEI integration requires dynamic loading through Fabric API.
  This namespace is a stub that delegates to jei_core for data retrieval.
  The actual JEI registration happens through Fabric's dynamic class loading
  and event system when JEI is present.

  JEI integration is optional - if JEI is not present, this module
  will not be loaded."
  (:require [cn.li.mc1201.integration.jei-core :as jei-core]
            [cn.li.mcmod.platform.integration-runtime :as integration-runtime]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.config :as mod-config]))

;; ============================================================================
;; Fabric JEI Registration Stubs
;; ============================================================================

(defn register-jei-categories
  "Register JEI categories (Fabric stub).
  
  In Fabric, actual registration happens through dynamic class loading
  and event callbacks. This function provides data to the actual
  JEI integration code that's loaded dynamically.
  
  Args:
    registration: Dynamic IRecipeCategoryRegistration instance
  
  Returns:
    nil"
  [registration]
  (try
    (log/info "Preparing JEI category registration data (Fabric)")
    (let [categories (jei-core/get-all-categories)]
      (when (seq categories)
        (log/info "Found" (count categories) "JEI categories to register")))
    nil
    (catch Exception e
      (log/error "Failed to prepare JEI category registration:" (ex-message e))
      nil)))

(defn register-jei-recipes
  "Register JEI recipes (Fabric stub).
  
  In Fabric, actual registration happens through dynamic class loading.
  This function provides recipe data to the actual JEI integration code.
  
  Args:
    registration: Dynamic IRecipeRegistration instance
  
  Returns:
    nil"
  [registration]
  (try
    (log/info "Preparing JEI recipe registration data (Fabric)")
    (let [categories (jei-core/get-all-categories)]
      (when (seq categories)
        (log/info "Preparing" (count categories) "recipe categories for JEI registration")))
    nil
    (catch Exception e
      (log/error "Failed to prepare JEI recipe registration:" (ex-message e))
      nil)))

(defn register-jei-catalysts
  "Register JEI catalysts (Fabric stub).
  
  In Fabric, actual registration happens through dynamic class loading.
  
  Args:
    registration: Dynamic IRecipeCatalystRegistration instance
  
  Returns:
    nil"
  [registration]
  (try
    (log/info "Preparing JEI catalyst registration data (Fabric)")
    nil
    (catch Exception e
      (log/error "Failed to prepare JEI catalyst registration:" (ex-message e))
      nil)))

(defn init-jei!
  "Initialize JEI integration (Fabric version).

  For Fabric, actual registration happens through event listeners
  and dynamic class loading. This initializes the data pipeline.
  
  Returns:
    nil"
  []
  (try
    (log/info "JEI integration initialized (Fabric - awaiting dynamic registration)")
    nil
    (catch Exception e
      (log/error "Failed to initialize Fabric JEI integration:" (ex-message e))
      nil)))

;; Note: For full Fabric JEI support, a separate Java plugin class using
;; Fabric's @Environment and event system would be needed. This stub
;; provides the data pipeline through mc1201/jei_core.
