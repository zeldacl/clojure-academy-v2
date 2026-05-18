(ns cn.li.ac.config.gameplay
  "Gameplay configuration owned by AC and exposed through mcmod config descriptors.

  Platform modules provide storage/backends only; gameplay defaults and typed
  accessors stay in AC so loader projects never import cn.li.ac.* directly."
  (:require [cn.li.ac.config.common :as config-common]
            [cn.li.mcmod.config.registry :as config-reg]
            [cn.li.mcmod.util.log :as log]))

(def default-generic-config
  {:attack-player true
   :destroy-blocks true
   :worlds-whitelisted-destroying-blocks []
   :use-mouse-wheel false
   :give-cloud-terminal true
   :font "Microsoft YaHei"})

(def default-ability-config
  {:normal-metal-blocks
   ["minecraft:rail"
    "minecraft:iron_bars"
    "minecraft:iron_block"
    "minecraft:activator_rail"
    "minecraft:detector_rail"
    "minecraft:golden_rail"
    "minecraft:sticky_piston"
    "minecraft:piston"
    "minecraft:iron_door"
    "minecraft:iron_trapdoor"
    "minecraft:heavy_weighted_pressure_plate"
    "minecraft:anvil"
    "minecraft:chipped_anvil"
    "minecraft:damaged_anvil"
    "minecraft:hopper"
    "minecraft:cauldron"]

   :weak-metal-blocks
   ["minecraft:dispenser"
    "minecraft:hopper"
    "minecraft:iron_ore"
    "minecraft:deepslate_iron_ore"
    "minecraft:raw_iron_block"]

   :metal-entities
   ["minecraft:minecart"
    "minecraft:chest_minecart"
    "minecraft:furnace_minecart"
    "minecraft:tnt_minecart"
    "minecraft:hopper_minecart"
    "minecraft:spawner_minecart"
    "minecraft:command_block_minecart"
    "my_mod:entity_mag_hook"
    "minecraft:iron_golem"]})

(def default-cp-overload-data
  {:cp-recover-cooldown 15
   :cp-recover-speed 1.0
   :overload-recover-cooldown 32
   :overload-recover-speed 1.0
   :maxcp-incr-rate 0.0025
   :maxo-incr-rate 0.0058
   :prog-incr-rate 1.0
   :init-cp [1800 1800 2800 4000 5800 8000]
   :add-cp [0 900 1000 1500 1700 12000]
   :init-overload [100 100 150 240 350 500]
   :add-overload [0 40 70 80 100 500]})

(def default-calc-global
  {:damage-scale 1.0})

(def default-values
  (merge default-generic-config
         default-ability-config
         default-cp-overload-data
         default-calc-global))

(def descriptors
  [{:key :attack-player
    :path "generic.attack-player"
    :section :generic
    :type :boolean
    :default (:attack-player default-values)
    :comment "Whether abilities are allowed to attack players."}
   {:key :destroy-blocks
    :path "generic.destroy-blocks"
    :section :generic
    :type :boolean
    :default (:destroy-blocks default-values)
    :comment "Whether abilities are allowed to destroy blocks."}
   {:key :worlds-whitelisted-destroying-blocks
    :path "generic.worlds-whitelisted-destroying-blocks"
    :section :generic
    :type :string-list
    :default (:worlds-whitelisted-destroying-blocks default-values)
    :comment "World IDs where block destruction is explicitly allowed."}
   {:key :use-mouse-wheel
    :path "generic.use-mouse-wheel"
    :section :generic
    :type :boolean
    :default (:use-mouse-wheel default-values)
    :comment "Whether AC GUI interactions may use mouse wheel shortcuts."}
   {:key :give-cloud-terminal
    :path "generic.give-cloud-terminal"
    :section :generic
    :type :boolean
    :default (:give-cloud-terminal default-values)
    :comment "Whether players receive a Cloud Terminal through AC flows."}
   {:key :font
    :path "generic.font"
    :section :generic
    :type :string
    :default (:font default-values)
    :comment "Preferred AC UI font name."}

   {:key :normal-metal-blocks
    :path "ability.normal-metal-blocks"
    :section :ability
    :type :string-list
    :default (:normal-metal-blocks default-values)
    :comment "Block IDs treated as normal metal blocks."}
   {:key :weak-metal-blocks
    :path "ability.weak-metal-blocks"
    :section :ability
    :type :string-list
    :default (:weak-metal-blocks default-values)
    :comment "Block IDs treated as weak metal blocks."}
   {:key :metal-entities
    :path "ability.metal-entities"
    :section :ability
    :type :string-list
    :default (:metal-entities default-values)
    :comment "Entity IDs treated as metal entities."}

   {:key :cp-recover-cooldown
    :path "cp-overload.cp-recover-cooldown"
    :section :cp-overload
    :type :int
    :min 0
    :default (:cp-recover-cooldown default-values)
    :comment "Cooldown ticks before CP starts recovering."}
   {:key :cp-recover-speed
    :path "cp-overload.cp-recover-speed"
    :section :cp-overload
    :type :double
    :min 0.0
    :default (:cp-recover-speed default-values)
    :comment "CP recovery speed multiplier."}
   {:key :overload-recover-cooldown
    :path "cp-overload.overload-recover-cooldown"
    :section :cp-overload
    :type :int
    :min 0
    :default (:overload-recover-cooldown default-values)
    :comment "Cooldown ticks before overload starts recovering."}
   {:key :overload-recover-speed
    :path "cp-overload.overload-recover-speed"
    :section :cp-overload
    :type :double
    :min 0.0
    :default (:overload-recover-speed default-values)
    :comment "Overload recovery speed multiplier."}
   {:key :maxcp-incr-rate
    :path "cp-overload.maxcp-incr-rate"
    :section :cp-overload
    :type :double
    :min 0.0
    :default (:maxcp-incr-rate default-values)
    :comment "Maximum CP growth rate."}
   {:key :maxo-incr-rate
    :path "cp-overload.maxo-incr-rate"
    :section :cp-overload
    :type :double
    :min 0.0
    :default (:maxo-incr-rate default-values)
    :comment "Maximum overload growth rate."}
   {:key :prog-incr-rate
    :path "cp-overload.prog-incr-rate"
    :section :cp-overload
    :type :double
    :min 0.0
    :default (:prog-incr-rate default-values)
    :comment "Ability progression growth rate."}
   {:key :init-cp
    :path "cp-overload.init-cp"
    :section :cp-overload
    :type :int-list
    :default (:init-cp default-values)
    :comment "Initial CP values for levels 0-5."}
   {:key :add-cp
    :path "cp-overload.add-cp"
    :section :cp-overload
    :type :int-list
    :default (:add-cp default-values)
    :comment "Additional CP values for levels 0-5."}
   {:key :init-overload
    :path "cp-overload.init-overload"
    :section :cp-overload
    :type :int-list
    :default (:init-overload default-values)
    :comment "Initial overload values for levels 0-5."}
   {:key :add-overload
    :path "cp-overload.add-overload"
    :section :cp-overload
    :type :int-list
    :default (:add-overload default-values)
    :comment "Additional overload values for levels 0-5."}

   {:key :damage-scale
    :path "calc.damage-scale"
    :section :calc
    :type :double
    :min 0.0
    :default (:damage-scale default-values)
    :comment "Global AC damage multiplier."}])

(defn- value
  [k]
  (get (config-common/gameplay-config) k (get default-values k)))

(defn level-value
  "Read a level-indexed numeric list. Out-of-range or non-numeric levels return 0."
  [values level]
  (let [idx (if (number? level) (int level) -1)]
    (get (vec (or values [])) idx 0)))

(defn list-predicate
  "Build a predicate over a dynamic string list getter."
  [values-fn]
  (fn [id]
    (contains? (set (map str (values-fn))) (str id))))

(defn init-config!
  "Ensure gameplay defaults are present in the shared config registry."
  []
  (config-reg/register-config-descriptors! config-common/gameplay-domain descriptors)
  (config-reg/ensure-default-values! config-common/gameplay-domain default-values)
  (log/info "Initialized gameplay config descriptors" {:domain config-common/gameplay-domain})
  nil)

(defn attack-player-enabled? []
  (boolean (value :attack-player)))

(defn destroy-blocks-enabled? []
  (boolean (value :destroy-blocks)))

(defn get-normal-metal-blocks []
  (vec (value :normal-metal-blocks)))

(defn get-weak-metal-blocks []
  (vec (value :weak-metal-blocks)))

(defn get-metal-entities []
  (vec (value :metal-entities)))

(defn is-normal-metal-block?
  [block-id]
  ((list-predicate get-normal-metal-blocks) block-id))

(defn is-weak-metal-block?
  [block-id]
  ((list-predicate get-weak-metal-blocks) block-id))

(defn is-metal-block?
  [block-id]
  (or (is-normal-metal-block? block-id)
      (is-weak-metal-block? block-id)))

(defn is-metal-entity?
  [entity-id]
  ((list-predicate get-metal-entities) entity-id))

(defn get-cp-recover-cooldown []
  (int (value :cp-recover-cooldown)))

(defn get-cp-recover-speed []
  (double (value :cp-recover-speed)))

(defn get-overload-recover-cooldown []
  (int (value :overload-recover-cooldown)))

(defn get-overload-recover-speed []
  (double (value :overload-recover-speed)))

(defn get-init-cp
  [level]
  (level-value (value :init-cp) level))

(defn get-add-cp
  [level]
  (level-value (value :add-cp) level))

(defn get-init-overload
  [level]
  (level-value (value :init-overload) level))

(defn get-add-overload
  [level]
  (level-value (value :add-overload) level))

(defn get-damage-scale []
  (double (value :damage-scale)))

(defn validate-config!
  "Validate currently effective gameplay configuration values."
  []
  (let [errors (atom [])]
    (doseq [[k values] {:init-cp (value :init-cp)
                        :add-cp (value :add-cp)
                        :init-overload (value :init-overload)
                        :add-overload (value :add-overload)}]
      (when (not= 6 (count values))
        (swap! errors conj (str (name k) " must have 6 elements"))))
    (when (<= (get-cp-recover-speed) 0)
      (swap! errors conj "cp-recover-speed must be positive"))
    (when (<= (get-overload-recover-speed) 0)
      (swap! errors conj "overload-recover-speed must be positive"))
    (when (<= (get-damage-scale) 0)
      (swap! errors conj "damage-scale must be positive"))
    (if (empty? @errors)
      (do
        (log/info "Gameplay configuration validation passed")
        nil)
      (do
        (log/error "Gameplay configuration validation failed:" @errors)
        (throw (ex-info "Invalid gameplay configuration" {:errors @errors}))))))
