(ns cn.li.fabric1201.config.gameplay-bridge
  "Bridge to access Fabric gameplay configuration from Clojure.

  Values are loaded from JSON under Fabric config dir and merged onto defaults
  from `cn.li.ac.config.gameplay` to keep cross-platform semantics aligned."
  (:require [clojure.java.io :as io]
            [cn.li.ac.config.gameplay :as gameplay]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.util.log :as log])
  (:import [com.google.gson GsonBuilder]
           [java.util LinkedHashMap]
           [net.fabricmc.loader.api FabricLoader]))

(defonce ^:private gson
  (delay (-> (GsonBuilder.)
             (.setPrettyPrinting)
             (.create))))

(defonce ^:private loaded? (atom false))
(defonce ^:private gameplay-config* (atom nil))

(defn- default-config
  []
  {:attack-player? (:attack-player gameplay/default-generic-config)
   :destroy-blocks? (:destroy-blocks gameplay/default-generic-config)
   :normal-metal-blocks (:normal-metal-blocks gameplay/default-ability-config)
   :weak-metal-blocks (:weak-metal-blocks gameplay/default-ability-config)
   :metal-entities (:metal-entities gameplay/default-ability-config)
   :cp-recover-cooldown (:cp-recover-cooldown gameplay/default-cp-overload-data)
   :cp-recover-speed (:cp-recover-speed gameplay/default-cp-overload-data)
   :overload-recover-cooldown (:overload-recover-cooldown gameplay/default-cp-overload-data)
   :overload-recover-speed (:overload-recover-speed gameplay/default-cp-overload-data)
   :init-cp-list (:init-cp gameplay/default-cp-overload-data)
   :add-cp-list (:add-cp gameplay/default-cp-overload-data)
   :init-overload-list (:init-overload gameplay/default-cp-overload-data)
   :add-overload-list (:add-overload gameplay/default-cp-overload-data)
   :damage-scale (:damage-scale gameplay/default-calc-global)})

(defn- config-file
  []
  (let [config-dir (-> (FabricLoader/getInstance) (.getConfigDir) (.toFile))]
    (io/file config-dir (str modid/MOD-ID "-gameplay.json"))))

(defn- read-json
  [file]
  (with-open [reader (io/reader file)]
    (.fromJson @gson reader java.util.Map)))

(defn- parse-bool [raw default]
  (cond
    (instance? Boolean raw) raw
    (string? raw) (Boolean/parseBoolean raw)
    :else default))

(defn- parse-int [raw default]
  (cond
    (number? raw) (int raw)
    (string? raw) (try (Integer/parseInt raw) (catch Exception _ default))
    :else default))

(defn- parse-double-value [raw default]
  (cond
    (number? raw) (double raw)
    (string? raw) (try (Double/parseDouble raw) (catch Exception _ default))
    :else default))

(defn- parse-str-list [raw default]
  (if (instance? java.util.List raw)
    (->> raw (map str) vec)
    default))

(defn- parse-int-list [raw default]
  (if (instance? java.util.List raw)
    (->> raw (map #(parse-int % 0)) vec)
    default))

(defn- read-value [parsed k]
  (or (get parsed k)
      (get parsed (name k))))

(defn- parse-config
  [parsed defaults]
  {:attack-player? (parse-bool (read-value parsed "attack-player") (:attack-player? defaults))
   :destroy-blocks? (parse-bool (read-value parsed "destroy-blocks") (:destroy-blocks? defaults))
   :normal-metal-blocks (parse-str-list (read-value parsed "normal-metal-blocks") (:normal-metal-blocks defaults))
   :weak-metal-blocks (parse-str-list (read-value parsed "weak-metal-blocks") (:weak-metal-blocks defaults))
   :metal-entities (parse-str-list (read-value parsed "metal-entities") (:metal-entities defaults))
  :cp-recover-cooldown (parse-int (read-value parsed "cp-recover-cooldown") (:cp-recover-cooldown defaults))
  :cp-recover-speed (parse-double-value (read-value parsed "cp-recover-speed") (:cp-recover-speed defaults))
   :overload-recover-cooldown (parse-int (read-value parsed "overload-recover-cooldown") (:overload-recover-cooldown defaults))
  :overload-recover-speed (parse-double-value (read-value parsed "overload-recover-speed") (:overload-recover-speed defaults))
   :init-cp-list (parse-int-list (read-value parsed "init-cp-list") (:init-cp-list defaults))
   :add-cp-list (parse-int-list (read-value parsed "add-cp-list") (:add-cp-list defaults))
   :init-overload-list (parse-int-list (read-value parsed "init-overload-list") (:init-overload-list defaults))
   :add-overload-list (parse-int-list (read-value parsed "add-overload-list") (:add-overload-list defaults))
  :damage-scale (parse-double-value (read-value parsed "damage-scale") (:damage-scale defaults))})

(defn- as-json-map
  [cfg]
  (doto (LinkedHashMap.)
    (.put "attack-player" (:attack-player? cfg))
    (.put "destroy-blocks" (:destroy-blocks? cfg))
    (.put "normal-metal-blocks" (:normal-metal-blocks cfg))
    (.put "weak-metal-blocks" (:weak-metal-blocks cfg))
    (.put "metal-entities" (:metal-entities cfg))
    (.put "cp-recover-cooldown" (:cp-recover-cooldown cfg))
    (.put "cp-recover-speed" (:cp-recover-speed cfg))
    (.put "overload-recover-cooldown" (:overload-recover-cooldown cfg))
    (.put "overload-recover-speed" (:overload-recover-speed cfg))
    (.put "init-cp-list" (:init-cp-list cfg))
    (.put "add-cp-list" (:add-cp-list cfg))
    (.put "init-overload-list" (:init-overload-list cfg))
    (.put "add-overload-list" (:add-overload-list cfg))
    (.put "damage-scale" (:damage-scale cfg))))

(defn- write-json!
  [file cfg]
  (when-let [parent (.getParentFile file)]
    (.mkdirs parent))
  (with-open [writer (io/writer file)]
    (.toJson @gson (as-json-map cfg) writer)))

(defn- ensure-loaded!
  []
  (when-not @loaded?
    (let [defaults (default-config)
          file (config-file)
          parsed (when (.exists file)
                   (try
                     (read-json file)
                     (catch Exception e
                       (log/warn "Failed reading Fabric gameplay config, using defaults:" (ex-message e))
                       nil)))
          merged (if parsed (parse-config parsed defaults) defaults)]
      (reset! gameplay-config* merged)
      (when (or (not (.exists file)) parsed)
        (write-json! file merged))
      (reset! loaded? true))))

(defn- cfg
  [k]
  (ensure-loaded!)
  (get @gameplay-config* k))

(defn attack-player? [] (boolean (cfg :attack-player?)))
(defn destroy-blocks? [] (boolean (cfg :destroy-blocks?)))

(defn get-normal-metal-blocks [] (vec (cfg :normal-metal-blocks)))
(defn get-weak-metal-blocks [] (vec (cfg :weak-metal-blocks)))
(defn get-metal-entities [] (vec (cfg :metal-entities)))

(def ^:private normal-metal-block?
  (gameplay/list-predicate get-normal-metal-blocks))

(def ^:private weak-metal-block?
  (gameplay/list-predicate get-weak-metal-blocks))

(def ^:private metal-entity?
  (gameplay/list-predicate get-metal-entities))

(defn is-metal-block? [block-id]
  (or (normal-metal-block? block-id)
      (weak-metal-block? block-id)))

(defn is-normal-metal-block? [block-id]
  (normal-metal-block? block-id))

(defn is-weak-metal-block? [block-id]
  (weak-metal-block? block-id))

(defn is-metal-entity? [entity-id]
  (metal-entity? entity-id))

(defn get-cp-recover-cooldown [] (int (cfg :cp-recover-cooldown)))
(defn get-cp-recover-speed [] (double (cfg :cp-recover-speed)))
(defn get-overload-recover-cooldown [] (int (cfg :overload-recover-cooldown)))
(defn get-overload-recover-speed [] (double (cfg :overload-recover-speed)))

(defn get-init-cp-list [] (vec (cfg :init-cp-list)))
(defn get-add-cp-list [] (vec (cfg :add-cp-list)))
(defn get-init-overload-list [] (vec (cfg :init-overload-list)))
(defn get-add-overload-list [] (vec (cfg :add-overload-list)))

(defn get-init-cp
  [level]
    (gameplay/level-value (get-init-cp-list) level))

(defn get-add-cp
  [level]
    (gameplay/level-value (get-add-cp-list) level))

(defn get-init-overload
  [level]
    (gameplay/level-value (get-init-overload-list) level))

(defn get-add-overload
  [level]
    (gameplay/level-value (get-add-overload-list) level))

(defn get-damage-scale [] (double (cfg :damage-scale)))

(defn init-fabric-gameplay-config!
  []
  (ensure-loaded!)
  (log/info "Fabric gameplay config initialized")
  nil)

(defn provider-map
  []
    (gameplay/make-provider-map
   {:attack-player? attack-player?
    :destroy-blocks? destroy-blocks?
    :get-normal-metal-blocks get-normal-metal-blocks
    :get-weak-metal-blocks get-weak-metal-blocks
    :get-metal-entities get-metal-entities
    :get-cp-recover-cooldown get-cp-recover-cooldown
    :get-cp-recover-speed get-cp-recover-speed
    :get-overload-recover-cooldown get-overload-recover-cooldown
    :get-overload-recover-speed get-overload-recover-speed
    :get-init-cp-list get-init-cp-list
    :get-add-cp-list get-add-cp-list
    :get-init-overload-list get-init-overload-list
    :get-add-overload-list get-add-overload-list
    :get-damage-scale get-damage-scale}))
