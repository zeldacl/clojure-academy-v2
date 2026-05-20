(ns cn.li.ac.ability.config
  "Ability system configuration descriptors and typed getters.

  AC owns the domain defaults and business-facing accessors. Platform projects
  only provide file storage (Forge TOML / Fabric JSON) through mcmod's config
  registry, matching the wireless configuration pattern.

  NO net.minecraft.* imports - this namespace is platform-neutral."
  (:require [clojure.string :as str]
            [cn.li.ac.config.common :as config-common]
            [cn.li.mcmod.config.registry :as config-reg]
            [cn.li.mcmod.util.log :as log]))

(def expected-level-count 5)

(def default-values
  {:attack-player true
   :destroy-blocks true

   ;; Defaults intentionally mirror the currently active MagMovement hardcoded
   ;; sets so extracting config does not silently change targeting behavior.
   :normal-metal-blocks
   ["minecraft:activator_rail"
    "minecraft:detector_rail"
    "minecraft:iron_bars"
    "minecraft:iron_block"
    "minecraft:piston"
    "minecraft:powered_rail"
    "minecraft:rail"
    "minecraft:sticky_piston"]
   :weak-metal-blocks
   ["minecraft:dispenser"
    "minecraft:hopper"
    "minecraft:iron_ore"]
   :metal-entities
   ["minecraft:chest_minecart"
    "minecraft:command_block_minecart"
    "minecraft:furnace_minecart"
    "minecraft:hopper_minecart"
    "minecraft:iron_golem"
    "minecraft:minecart"
    "minecraft:spawner_minecart"
    "minecraft:tnt_minecart"
    "my_mod:entity_mag_hook"]

   :init-cp [1800.0 2800.0 4000.0 5800.0 8000.0]
   :add-cp [900.0 1000.0 1500.0 1700.0 12000.0]
   :init-overload [100.0 150.0 240.0 350.0 500.0]
   :add-overload [40.0 70.0 80.0 100.0 500.0]
   :cp-recover-cooldown 15
   :cp-recover-speed 1.0
   :overload-recover-cooldown 32
   :overload-recover-speed 1.0
   :maxcp-incr-rate 0.0025
   :maxo-incr-rate 0.0058
   :damage-scale 1.0
   :runtime-cp-consume-per-tick 1.0
   :runtime-overload-per-tick 0.6
   :prog-incr-rate 1.0})

(def descriptors
  [{:key :attack-player
    :path "general.attack-player"
    :section :general
    :type :boolean
    :default (:attack-player default-values)
    :comment "Whether ability skills may damage players."}
   {:key :destroy-blocks
    :path "general.destroy-blocks"
    :section :general
    :type :boolean
    :default (:destroy-blocks default-values)
    :comment "Whether ability skills may destroy blocks by default."}

   {:key :normal-metal-blocks
    :path "targeting.metal.normal-metal-blocks"
    :section :targeting.metal
    :type :string-list
    :default (:normal-metal-blocks default-values)
    :comment "Block IDs treated as normal metal targets by magnetic abilities."}
   {:key :weak-metal-blocks
    :path "targeting.metal.weak-metal-blocks"
    :section :targeting.metal
    :type :string-list
    :default (:weak-metal-blocks default-values)
    :comment "Block IDs treated as weak metal targets by magnetic abilities."}
   {:key :metal-entities
    :path "targeting.metal.metal-entities"
    :section :targeting.metal
    :type :string-list
    :default (:metal-entities default-values)
    :comment "Entity IDs treated as metal targets by magnetic abilities."}

   {:key :init-cp
    :path "resource.init-cp"
    :section :resource
    :type :double-list
    :min 0.0
    :default (:init-cp default-values)
    :comment "Initial max CP for ability levels 1-5."}
   {:key :add-cp
    :path "resource.add-cp"
    :section :resource
    :type :double-list
    :min 0.0
    :default (:add-cp default-values)
    :comment "Maximum CP growth ceiling for ability levels 1-5."}
   {:key :init-overload
    :path "resource.init-overload"
    :section :resource
    :type :double-list
    :min 0.0
    :default (:init-overload default-values)
    :comment "Initial max overload for ability levels 1-5."}
   {:key :add-overload
    :path "resource.add-overload"
    :section :resource
    :type :double-list
    :min 0.0
    :default (:add-overload default-values)
    :comment "Maximum overload growth ceiling for ability levels 1-5."}
   {:key :cp-recover-cooldown
    :path "resource.cp-recover-cooldown"
    :section :resource
    :type :int
    :min 0
    :default (:cp-recover-cooldown default-values)
    :comment "Ticks to wait after CP use before recovery starts."}
   {:key :cp-recover-speed
    :path "resource.cp-recover-speed"
    :section :resource
    :type :double
    :min 0.0
    :default (:cp-recover-speed default-values)
    :comment "CP recovery speed multiplier."}
   {:key :overload-recover-cooldown
    :path "resource.overload-recover-cooldown"
    :section :resource
    :type :int
    :min 0
    :default (:overload-recover-cooldown default-values)
    :comment "Ticks to wait after overload use before recovery starts."}
   {:key :overload-recover-speed
    :path "resource.overload-recover-speed"
    :section :resource
    :type :double
    :min 0.0
    :default (:overload-recover-speed default-values)
    :comment "Overload recovery speed multiplier."}
   {:key :maxcp-incr-rate
    :path "resource.maxcp-incr-rate"
    :section :resource
    :type :double
    :min 0.0
    :default (:maxcp-incr-rate default-values)
    :comment "Fraction of consumed CP added to max CP growth."}
   {:key :maxo-incr-rate
    :path "resource.maxo-incr-rate"
    :section :resource
    :type :double
    :min 0.0
    :default (:maxo-incr-rate default-values)
    :comment "Fraction of overload added to max overload growth."}

   {:key :damage-scale
    :path "combat.damage-scale"
    :section :combat
    :type :double
    :min 0.0
    :default (:damage-scale default-values)
    :comment "Global ability damage multiplier."}

   {:key :runtime-cp-consume-per-tick
    :path "runtime.cp-consume-per-tick"
    :section :runtime
    :type :double
    :min 0.0
    :default (:runtime-cp-consume-per-tick default-values)
    :comment "Default CP cost for runtime-speed ticking skills."}
   {:key :runtime-overload-per-tick
    :path "runtime.overload-per-tick"
    :section :runtime
    :type :double
    :min 0.0
    :default (:runtime-overload-per-tick default-values)
    :comment "Default overload cost for runtime-speed ticking skills."}

   {:key :prog-incr-rate
    :path "progression.prog-incr-rate"
    :section :progression
    :type :double
    :min 0.0
    :default (:prog-incr-rate default-values)
    :comment "Global multiplier applied to ability experience and level progress."}])

(defn- raw-value
  [k]
  (get (config-common/ability-config) k (get default-values k)))

(defn- finite-double
  [v default]
  (try
    (let [d (cond
              (number? v) (double v)
              (string? v) (Double/parseDouble v)
              :else (double default))]
      (if (or (Double/isNaN d) (Double/isInfinite d))
        (double default)
        d))
    (catch Exception _
      (double default))))

(defn- non-negative-double
  [k]
  (let [default (double (get default-values k))
        value (finite-double (raw-value k) default)]
    (if (neg? value) default value)))

(defn- positive-double
  [k]
  (let [default (double (get default-values k))
        value (finite-double (raw-value k) default)]
    (if (pos? value) value default)))

(defn- non-negative-int
  [k]
  (max 0 (int (Math/round (non-negative-double k)))))

(defn- boolean-value
  [k]
  (let [v (raw-value k)]
    (cond
      (instance? Boolean v) v
      (string? v) (Boolean/parseBoolean v)
      :else (boolean (get default-values k)))))

(defn- list-like?
  [v]
  (and (not (string? v))
       (seqable? v)))

(defn- string-list
  [k]
  (let [v (raw-value k)
        fallback (vec (get default-values k))]
    (if (list-like? v)
      (->> v
           (keep #(let [s (some-> % str str/trim)]
                    (when-not (str/blank? s) s)))
           vec)
      fallback)))

(defn normalized-level-list
  "Return a validated level-indexed numeric list for levels 1-5. Invalid
  length or invalid entries fall back to the descriptor default."
  [k]
  (let [fallback (vec (get default-values k))
        raw (raw-value k)]
    (if (and (list-like? raw)
             (= expected-level-count (count raw)))
      (mapv (fn [value default]
              (let [d (finite-double value default)]
                (if (neg? d) (double default) d)))
            raw
            fallback)
      fallback)))

(defn level-value
  "Read a 1-based ability level value from a normalized level list."
  [values level]
  (let [idx (if (number? level) (dec (int level)) -1)]
    (if (<= 0 idx (dec expected-level-count))
      (double (nth values idx))
      0.0)))

(defn init-config!
  "Ensure ability defaults are present in the shared config registry."
  []
  (config-reg/register-config-descriptors! config-common/ability-domain descriptors)
  (config-reg/ensure-default-values! config-common/ability-domain default-values)
  (log/info "Initialized ability config descriptors" {:domain config-common/ability-domain})
  nil)

(defn attack-player-enabled? []
  (boolean-value :attack-player))

(defn destroy-blocks-enabled? []
  (boolean-value :destroy-blocks))

(defn get-normal-metal-blocks []
  (string-list :normal-metal-blocks))

(defn get-weak-metal-blocks []
  (string-list :weak-metal-blocks))

(defn get-metal-entities []
  (string-list :metal-entities))

(defn- list-predicate
  [values-fn]
  (fn [id]
    (contains? (set (map str (values-fn))) (str id))))

(defn is-normal-metal-block? [block-id]
  ((list-predicate get-normal-metal-blocks) block-id))

(defn is-weak-metal-block? [block-id]
  ((list-predicate get-weak-metal-blocks) block-id))

(defn is-metal-block? [block-id]
  (or (is-normal-metal-block? block-id)
      (is-weak-metal-block? block-id)))

(defn is-metal-entity? [entity-id]
  ((list-predicate get-metal-entities) entity-id))

(defn get-init-cp [level]
  (level-value (normalized-level-list :init-cp) level))

(defn get-add-cp [level]
  (level-value (normalized-level-list :add-cp) level))

(defn get-init-overload [level]
  (level-value (normalized-level-list :init-overload) level))

(defn get-add-overload [level]
  (level-value (normalized-level-list :add-overload) level))

(defn add-cp-ceiling [level]
  (get-add-cp level))

(defn add-overload-ceiling [level]
  (get-add-overload level))

(defn max-cp-for-level
  "Compute max CP for a given ability level (1-5)."
  [level]
  {:pre [(>= level 1) (<= level 5)]}
  (+ (get-init-cp level)
     (get-add-cp level)))

(defn max-overload-for-level
  "Compute max overload for a given ability level (1-5)."
  [level]
  {:pre [(>= level 1) (<= level 5)]}
  (+ (get-init-overload level)
     (get-add-overload level)))

(defn cp-recover-cooldown []
  (non-negative-int :cp-recover-cooldown))

(defn cp-recover-speed []
  (positive-double :cp-recover-speed))

(defn overload-recover-cooldown []
  (non-negative-int :overload-recover-cooldown))

(defn overload-recover-speed []
  (positive-double :overload-recover-speed))

(defn maxcp-incr-rate []
  (non-negative-double :maxcp-incr-rate))

(defn maxo-incr-rate []
  (non-negative-double :maxo-incr-rate))

(defn damage-scale []
  (positive-double :damage-scale))

(defn runtime-cp-consume-per-tick []
  (non-negative-double :runtime-cp-consume-per-tick))

(defn runtime-overload-per-tick []
  (non-negative-double :runtime-overload-per-tick))

(defn prog-incr-rate []
  (positive-double :prog-incr-rate))

(defn validate-config!
  "Validate currently effective ability configuration values. Getters remain
  defensive at runtime; this function is for diagnostics/tests."
  []
  (let [errors (atom [])]
    (doseq [k [:init-cp :add-cp :init-overload :add-overload]
            :let [values (raw-value k)]]
      (when-not (and (list-like? values)
                     (= expected-level-count (count values))
                     (every? #(and (number? %) (not (neg? (double %)))) values))
        (swap! errors conj (str (name k) " must be a non-negative numeric list with 5 elements"))))
    (doseq [k [:normal-metal-blocks :weak-metal-blocks :metal-entities]
            :let [values (raw-value k)]]
      (when-not (and (list-like? values) (every? string? values))
        (swap! errors conj (str (name k) " must be a string list"))))
    (doseq [k [:cp-recover-speed :overload-recover-speed :damage-scale :prog-incr-rate]
            :let [value (finite-double (raw-value k) 0.0)]]
      (when-not (pos? value)
        (swap! errors conj (str (name k) " must be positive"))))
    (doseq [k [:cp-recover-cooldown :overload-recover-cooldown
               :maxcp-incr-rate :maxo-incr-rate
               :runtime-cp-consume-per-tick :runtime-overload-per-tick]
            :let [value (finite-double (raw-value k) -1.0)]]
      (when (neg? value)
        (swap! errors conj (str (name k) " must be non-negative"))))
    (if (empty? @errors)
      (do
        (log/info "Ability configuration validation passed")
        nil)
      (do
        (log/error "Ability configuration validation failed:" @errors)
        (throw (ex-info "Invalid ability configuration" {:errors @errors}))))))
