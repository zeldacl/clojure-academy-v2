(ns cn.li.forge1201.config.bridge
  (:require [clojure.string :as str]
            [cn.li.mcmod.config.registry :as config-reg]
            [cn.li.mcmod.util.log :as log])
  (:import [java.util.function Consumer]
           [net.minecraftforge.common ForgeConfigSpec ForgeConfigSpec$Builder]
           [net.minecraftforge.eventbus.api EventPriority]
           [net.minecraftforge.fml ModLoadingContext]
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
    (.comment builder (into-array String [(str comment)]))))

(defn- define-entry!
  [^ForgeConfigSpec$Builder builder descriptor]
  (let [default (:default descriptor)
        min-val (:min descriptor)
        max-val (:max descriptor)
        leaf (leaf-name descriptor)
        builder (comment-builder builder (:comment descriptor))]
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
                            [config-key (.get entry)]))
                     entries)]
    (config-reg/set-config-values! domain values)
    (log/info "Loaded Forge config domain" domain "from" file-name)))

(defn- handle-config-event!
  [event]
  (let [file-name (some-> event .getConfig .getFileName)]
    (when-let [domain-info (get @registered-configs file-name)]
      (load-domain-values! domain-info))))

(defn register-all!
  [mod-bus]
  (let [domains (seq (config-reg/get-all-config-domains))
        loading-class (Class/forName "net.minecraftforge.fml.event.config.ModConfigEvent$Loading")
        reloading-class (Class/forName "net.minecraftforge.fml.event.config.ModConfigEvent$Reloading")]
    (doseq [domain domains]
      (let [descriptors (config-reg/get-config-descriptors domain)]
        (when (seq descriptors)
          (let [{:keys [file-name spec] :as domain-info} (build-domain-spec domain descriptors)]
            (.registerConfig (ModLoadingContext/get) ModConfig$Type/COMMON spec file-name)
            (swap! registered-configs assoc file-name domain-info)
            (log/info "Registered Forge config file" file-name "for domain" domain)))))
    (.addListener mod-bus EventPriority/NORMAL false loading-class
                  (reify Consumer
                    (accept [_ event]
                      (handle-config-event! event))))
    (.addListener mod-bus EventPriority/NORMAL false reloading-class
                  (reify Consumer
                    (accept [_ event]
                      (handle-config-event! event))))
    nil))