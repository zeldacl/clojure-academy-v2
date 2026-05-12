(ns cn.li.forge1201.config.bridge
  (:require [clojure.string :as str]
            [cn.li.mcmod.config.registry :as config-reg]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.forge1201.bridge ConfigEventBridge]
           [cn.li.forge1201.config GameplayConfig]
           [java.util.function Consumer]
            [net.minecraftforge.common ForgeConfigSpec$Builder ForgeConfigSpec$ConfigValue]
           [net.minecraftforge.eventbus.api IEventBus]
           [net.minecraftforge.fml ModLoadingContext]
           [net.minecraftforge.fml.event.config ModConfigEvent]
           [net.minecraftforge.fml.config ModConfig$Type]))

(defonce registered-configs
  (atom {}))

(defn- domain->file-name
  [domain extension]
  (let [ns-part (namespace domain)
        name-part (name domain)]
    (str ns-part "-" name-part extension)))

(defn- section-segments
  [descriptor]
  (->> (str/split (or (some-> descriptor :section name)
                      (str/join "." (butlast (str/split (:path descriptor) #"\."))))
                  #"\.")
       (remove str/blank?)))

(defn- leaf-name
  [descriptor]
  (last (str/split (:path descriptor) #"\.")))

(defn- comment-builder
  [^ForgeConfigSpec$Builder builder comment]
  (if (str/blank? (str comment))
    builder
    (.comment builder ^"[Ljava.lang.String;" (into-array String [(str comment)]))))

(defn- define-entry!
  [^ForgeConfigSpec$Builder builder descriptor]
  (let [default (:default descriptor)
        min-val (:min descriptor)
        max-val (:max descriptor)
      ^String leaf (leaf-name descriptor)
        ^ForgeConfigSpec$Builder builder (comment-builder builder (:comment descriptor))]
    (case (:type descriptor)
      :int (.defineInRange builder leaf (int default) (int (or min-val Integer/MIN_VALUE)) (int (or max-val Integer/MAX_VALUE)))
      :double (.defineInRange builder leaf (double default) (double (or min-val Double/MIN_VALUE)) (double (or max-val Double/MAX_VALUE)))
      :boolean (.define builder leaf (boolean default))
      :string (.define builder leaf (str default))
      (throw (ex-info "Unsupported Forge config descriptor type"
                      {:descriptor descriptor})))))

(defn- build-domain-spec
  [domain descriptors]
  (let [builder (ForgeConfigSpec$Builder.)
        entries (into {}
                      (for [descriptor descriptors]
                        (let [sections (section-segments descriptor)]
                          (doseq [section sections]
                            (.push builder ^String section))
                          (let [entry (define-entry! builder descriptor)]
                            (dotimes [_ (count sections)]
                              (.pop builder))
                            [(:key descriptor) entry]))))
        spec (.build builder)
        file-name (domain->file-name domain ".toml")]
    {:domain domain
     :file-name file-name
     :spec spec
     :entries entries}))

(defn- load-domain-values!
  [{:keys [domain file-name entries]}]
  (let [values (into {}
                     (map (fn [[config-key entry]]
                            [config-key (.get ^ForgeConfigSpec$ConfigValue entry)]))
                     entries)]
    (config-reg/set-config-values! domain values)
    (log/info "Loaded Forge config domain" domain "from" file-name)))

(defn- handle-config-event!
  [^ModConfigEvent event]
  (let [file-name (some-> event .getConfig .getFileName)]
    (when-let [domain-info (get @registered-configs file-name)]
      (load-domain-values! domain-info))))

(defn register-all!
  [^IEventBus mod-bus]
  (let [domains (seq (config-reg/get-all-config-domains))]
    (doseq [domain domains]
      (let [descriptors (config-reg/get-config-descriptors domain)]
        (when (seq descriptors)
          (let [{:keys [file-name spec] :as domain-info} (build-domain-spec domain descriptors)]
            (.registerConfig (ModLoadingContext/get) ModConfig$Type/COMMON spec file-name)
            (swap! registered-configs assoc file-name domain-info)
            (log/info "Registered Forge config file" file-name "for domain" domain)))))
    (ConfigEventBridge/addConfigListeners mod-bus
                                          (reify Consumer
                                            (accept [_ event]
                                              (handle-config-event! event))))
    nil))

;; ---------------------------------------------------------------------------
;; GameplayConfig facade (merged from gameplay-bridge)
;; ---------------------------------------------------------------------------

(defn analysis-enabled? []
  (.get GameplayConfig/ANALYSIS_ENABLED))

(defn attack-player? []
  (.get GameplayConfig/ATTACK_PLAYER))

(defn destroy-blocks? []
  (.get GameplayConfig/DESTROY_BLOCKS))

(defn gen-ores? []
  (.get GameplayConfig/GEN_ORES))

(defn gen-phase-liquid? []
  (.get GameplayConfig/GEN_PHASE_LIQUID))

(defn heads-or-tails? []
  (.get GameplayConfig/HEADS_OR_TAILS))

(defn get-normal-metal-blocks []
  (vec (.get GameplayConfig/NORMAL_METAL_BLOCKS)))

(defn get-weak-metal-blocks []
  (vec (.get GameplayConfig/WEAK_METAL_BLOCKS)))

(defn get-metal-entities []
  (vec (.get GameplayConfig/METAL_ENTITIES)))

(defn is-metal-block? [block-id]
  (let [normal (set (get-normal-metal-blocks))
        weak (set (get-weak-metal-blocks))]
    (or (contains? normal block-id)
        (contains? weak block-id))))

(defn is-normal-metal-block? [block-id]
  (contains? (set (get-normal-metal-blocks)) block-id))

(defn is-weak-metal-block? [block-id]
  (contains? (set (get-weak-metal-blocks)) block-id))

(defn is-metal-entity? [entity-id]
  (contains? (set (get-metal-entities)) entity-id))

(defn get-cp-recover-cooldown []
  (.get GameplayConfig/CP_RECOVER_COOLDOWN))

(defn get-cp-recover-speed []
  (.get GameplayConfig/CP_RECOVER_SPEED))

(defn get-overload-recover-cooldown []
  (.get GameplayConfig/OVERLOAD_RECOVER_COOLDOWN))

(defn get-overload-recover-speed []
  (.get GameplayConfig/OVERLOAD_RECOVER_SPEED))

(defn get-init-cp-list []
  (vec (.get GameplayConfig/INIT_CP)))

(defn get-add-cp-list []
  (vec (.get GameplayConfig/ADD_CP)))

(defn get-init-overload-list []
  (vec (.get GameplayConfig/INIT_OVERLOAD)))

(defn get-add-overload-list []
  (vec (.get GameplayConfig/ADD_OVERLOAD)))

(defn get-init-cp
  [level]
  (let [cp-list (get-init-cp-list)]
    (if (and (>= level 0) (< level (count cp-list)))
      (nth cp-list level)
      (last cp-list))))

(defn get-add-cp
  [level]
  (let [cp-list (get-add-cp-list)]
    (if (and (>= level 0) (< level (count cp-list)))
      (nth cp-list level)
      (last cp-list))))

(defn get-init-overload
  [level]
  (let [overload-list (get-init-overload-list)]
    (if (and (>= level 0) (< level (count overload-list)))
      (nth overload-list level)
      (last overload-list))))

(defn get-add-overload
  [level]
  (let [overload-list (get-add-overload-list)]
    (if (and (>= level 0) (< level (count overload-list)))
      (nth overload-list level)
      (last overload-list))))

(defn get-damage-scale []
  (.get GameplayConfig/DAMAGE_SCALE))

(defn provider-map
  []
  {:analysis-enabled? analysis-enabled?
   :attack-player? attack-player?
   :destroy-blocks? destroy-blocks?
   :gen-ores? gen-ores?
   :gen-phase-liquid? gen-phase-liquid?
   :heads-or-tails? heads-or-tails?
   :get-normal-metal-blocks get-normal-metal-blocks
   :get-weak-metal-blocks get-weak-metal-blocks
   :get-metal-entities get-metal-entities
   :is-normal-metal-block? is-normal-metal-block?
   :is-weak-metal-block? is-weak-metal-block?
   :is-metal-block? is-metal-block?
   :is-metal-entity? is-metal-entity?
   :get-cp-recover-cooldown get-cp-recover-cooldown
   :get-cp-recover-speed get-cp-recover-speed
   :get-overload-recover-cooldown get-overload-recover-cooldown
   :get-overload-recover-speed get-overload-recover-speed
   :get-init-cp get-init-cp
   :get-add-cp get-add-cp
   :get-init-overload get-init-overload
   :get-add-overload get-add-overload
   :get-damage-scale get-damage-scale})