(ns cn.li.mc1201.client.key-mapping-adapter
  "Vanilla KeyMapping registration for :alternative scheme inputs.

   Create and register Minecraft KeyMappings from AC configuration.
   Loaders only call these helpers from their client setup / input events."
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.spi.keybinding-registry :as kb-registry]
            [cn.li.mcmod.config.registry :as config-reg]
            [cn.li.mcbase.glfw-polling-core :as glfw-polling])
  (:import [net.minecraft.client KeyMapping Minecraft]
           [com.mojang.blaze3d.platform InputConstants InputConstants$Key InputConstants$Type]
           [cn.li.mc1201.client KeyMappingAccess]))

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
    (KeyMappingAccess/boundKeyDisplayString km)))

(defn get-key-code
  "Current GLFW key code for a registered KeyMapping, or nil."
  [input-id]
  (when-let [^KeyMapping km (get-key-mapping input-id)]
    (Integer/valueOf (KeyMappingAccess/boundKeyValue km))))

(defn binding-conflict?
  "True when another registered mapping (vanilla or another mod) shares the
   bound key — the same conflict the vanilla Options > Controls list marks in
   red. Unbound mappings can never conflict."
  [input-id]
  (when-let [^KeyMapping km (get-key-mapping input-id)]
    (let [key (KeyMappingAccess/getKey km)]
      (when-not (.equals key InputConstants/UNKNOWN)
        (boolean
          (some (fn [^KeyMapping other]
                  (and (not (identical? other km))
                       (.same other km)))
                (seq (.keyMappings (.options ^Minecraft (Minecraft/getInstance))))))))))

(defn- key-code->input-key
  "Resolve an AC key-code to an InputConstants.Key. The settings app stores
   mouse buttons as -100+button (KeyMappingAccess/acKeyCode convention),
   everything else is a GLFW KEYSYM — vanilla Options > Controls binds
   keyboard and mouse keys alike."
  ^InputConstants$Key [key-code]
  (let [code (int key-code)]
    (if (< code 0)
      (.getOrCreate InputConstants$Type/MOUSE (+ 100 code))
      (.getOrCreate InputConstants$Type/KEYSYM code))))

(defn set-key-mapping-key!
  "Rebind a registered KeyMapping exactly the way vanilla Options > Controls
   does (KeyBindsScreen): first clear every OTHER mapping bound to the same
   key — the vanilla conflict policy resets conflicting vanilla/mod keybinds
   to UNKNOWN — then bind the new key (keyboard KEYSYM or mouse MOUSE), rebuild
   the lookup (KeyMapping.resetMapping) and persist through options.save().
   Returns true on success, nil if input-id isn't registered."
  [input-id key-code]
  (when-let [^KeyMapping km (get-key-mapping input-id)]
    (let [key (key-code->input-key key-code)
          mc (Minecraft/getInstance)
          options (.options mc)]
      (when-not (.equals key InputConstants/UNKNOWN)
        ;; Vanilla KeyBindsScreen conflict resolution: any OTHER mapping
        ;; (vanilla or another mod) on the same key is unbound, so the new
        ;; binding is authoritative instead of two mappings fighting.
        (doseq [^KeyMapping other (.keyMappings options)]
          (when (and (not (identical? other km))
                     (.equals key (KeyMappingAccess/getKey other)))
            (.setKey other InputConstants/UNKNOWN))))
      (.setKey km key)
      (KeyMapping/resetMapping)
      (.save options)
      true)))

;; AC gameplay config domain + Settings-owned key config keys (mirrors
;; cn.li.ac.config.gameplay — loaders must not import ac directly).
(def ^:private gameplay-domain :cn.li.ac/gameplay)
(def ^:private slot-config-keys
  [:ability-key-0 :ability-key-1 :ability-key-2 :ability-key-3])

(defn install-bound-key-resolver!
  "Wire the polling bound-key resolver (glfw-polling-core): [:bridge input-id]
   reads the KeyMapping's CURRENT binding, [:slot n] / [:screen kw] read the
   AC Settings-owned config keys. Lets GLFW polling follow Settings app /
   Options > Controls rebinds on platforms without KeyMapping events (Fabric),
   and fixes slot/screen keys for Forge's polling key-state-fn too."
  []
  (glfw-polling/install-bound-key-resolver!
    (fn [input-ref]
      (case (first input-ref)
        :bridge (get-key-code (second input-ref))
        :slot (config-reg/get-config-value
                gameplay-domain
                (nth slot-config-keys (second input-ref) nil))
        :screen (config-reg/get-config-value
                  gameplay-domain
                  (case (second input-ref)
                    :primary :edit-preset-key
                    :secondary nil))
        nil)))
  nil)

(defn register-all-keybindings-from-ac!
  "Bootstrap function: Register all :alternative scheme keybindings from content modules.

   Prerequisites:
   - Content modules must have registered keybinding configs via
     mcmod.spi.keybinding-registry (triggered by lifecycle/run-post-spi-client-init!)

   This function:
   1. Reads all registered keybinding configs from the neutral registry
   2. Extracts all :alternative scheme entries
   3. Registers each as a vanilla KeyMapping"
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
