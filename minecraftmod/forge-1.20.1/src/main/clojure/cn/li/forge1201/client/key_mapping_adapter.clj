(ns cn.li.forge1201.client.key-mapping-adapter
  "Forge KeyMapping registration for :alternative scheme inputs.
   
   Purpose: Create and register Minecraft KeyMappings from AC's configuration.
   Platform-specific code that Forge requires for key remapping UI."
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.ac.input-ids :as ac-input-ids])
  (:import [net.minecraft.client KeyMapping]))

;; ===== KeyMapping Registry =====
;; Stores KeyMappings created from AC configuration.
;; Forge's KeyMapping constructor automatically registers them with Minecraft.

(def ^:private registered-key-mappings (atom {}))

(defn register-alternative-key-mapping!
  "Register a single :alternative scheme key from AC config.
   
   Args:
   - input-id: keyword (e.g., :content/slot-0)
   - key-code: integer (GLFW_KEY_* constant)
   - translation-key: string for i18n (e.g., 'key.content.slot.0')
   - category: string (keybind category for UI organization)
   
   Returns: KeyMapping object
   
   Called during bootstrap to create all available keybindings.
   KeyMapping constructor side-effects: registers with Minecraft's key tracking."
  [input-id key-code translation-key category]
  (try
    (let [key-mapping (KeyMapping. translation-key key-code category)]
      (swap! registered-key-mappings assoc input-id key-mapping)
      (log/debug "Registered KeyMapping" 
                {:input-id input-id
                 :key-code key-code
                 :translation-key translation-key})
      key-mapping)
    (catch Exception e
      (log/error e "Failed to register KeyMapping"
                {:input-id input-id})
      nil)))

(defn get-key-mapping
  "Query a registered KeyMapping by input-id.
   
   Returns: KeyMapping or nil if not found"
  [input-id]
  (get @registered-key-mappings input-id))

(defn get-all-key-mappings
  "Get all registered KeyMappings (used by polling for active keys)"
  []
  (vals @registered-key-mappings))

(defn get-key-mappings-by-input-id
  "Get registered KeyMappings map keyed by input-id.

   Returns: {input-id -> KeyMapping}"
  []
  @registered-key-mappings)

(defn register-all-keybindings-from-ac!
  "Bootstrap function: Register all :alternative scheme keybindings from AC.
   
   Prerequisites:
   - AC must have provided configuration via cn.li.ac.input-ids
   
   This function:
   1. Requires AC input_ids namespace
   2. Extracts all :alternative scheme entries
   3. Registers each as a Forge KeyMapping"
  []
  (try
    (let [all-configs (ac-input-ids/get-input-ids)]
      (doseq [[_key config] all-configs]
        (when (= :alternative (:scheme config))
          (let [{:keys [input-id key-mapping]} config
                {:keys [key translation-key category]} key-mapping]
            (register-alternative-key-mapping! input-id key translation-key category)))))
    
    (log/info "Registered all AC alternative keybindings")
    nil
    
    (catch Exception e
      (log/error e "Failed to register AC keybindings"))))

(defn reset-for-test!
  "Clear all registered mappings (testing only)"
  []
  (reset! registered-key-mappings {})
  nil)
