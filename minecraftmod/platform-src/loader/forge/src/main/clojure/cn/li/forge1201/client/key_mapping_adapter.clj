(ns cn.li.forge1201.client.key-mapping-adapter
  "Forge KeyMapping registration for :alternative scheme inputs.
   
   Purpose: Create and register Minecraft KeyMappings from AC's configuration.
   Platform-specific code that Forge requires for key remapping UI."
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.spi.keybinding-registry :as kb-registry])
  (:import [net.minecraft.client KeyMapping Minecraft]
           [com.mojang.blaze3d.platform InputConstants$Type]))

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

(defn get-key-display-name
  "Localized display name of a registered KeyMapping's current bound key
   (e.g. \"C\", \"Left Alt\"), or nil if input-id isn't registered.
   Backs the terminal Settings app's 'keys' category rebind rows."
  [input-id]
  (when-let [^KeyMapping km (get-key-mapping input-id)]
    (.getString (.getDisplayName (.getKey km)))))

(defn set-key-mapping-key!
  "Rebind a registered KeyMapping to a new keyboard key-code and persist via
   vanilla options.txt — the same path Options > Controls uses. Returns true
   on success, nil if input-id isn't registered."
  [input-id key-code]
  (when-let [^KeyMapping km (get-key-mapping input-id)]
    (.setKey km (.getOrCreate InputConstants$Type/KEYSYM (int key-code)))
    (KeyMapping/resetMapping)
    (.save (.options ^Minecraft (Minecraft/getInstance)))
    true))

(defn register-all-keybindings-from-ac!
  "Bootstrap function: Register all :alternative scheme keybindings from content modules.

   Prerequisites:
   - Content modules must have registered keybinding configs via
     mcmod.spi.keybinding-registry (triggered by lifecycle/run-post-spi-client-init!)

   This function:
   1. Reads all registered keybinding configs from the neutral registry
   2. Extracts all :alternative scheme entries
   3. Registers each as a Forge KeyMapping"
  []
  (try
    (let [all-configs (kb-registry/get-all-keybinding-configs)]
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
