(ns cn.li.forge1201.config.gameplay-bridge
  "Compatibility facade; gameplay accessors moved to cn.li.forge1201.config.game-config."
  (:require [cn.li.forge1201.config.game-config :as game-config]))

(defn analysis-enabled? [] (game-config/analysis-enabled?))
(defn attack-player? [] (game-config/attack-player?))
(defn destroy-blocks? [] (game-config/destroy-blocks?))
(defn gen-ores? [] (game-config/gen-ores?))
(defn gen-phase-liquid? [] (game-config/gen-phase-liquid?))
(defn heads-or-tails? [] (game-config/heads-or-tails?))

(defn get-normal-metal-blocks [] (game-config/get-normal-metal-blocks))
(defn get-weak-metal-blocks [] (game-config/get-weak-metal-blocks))
(defn get-metal-entities [] (game-config/get-metal-entities))
(defn is-metal-block? [block-id] (game-config/is-metal-block? block-id))
(defn is-normal-metal-block? [block-id] (game-config/is-normal-metal-block? block-id))
(defn is-weak-metal-block? [block-id] (game-config/is-weak-metal-block? block-id))
(defn is-metal-entity? [entity-id] (game-config/is-metal-entity? entity-id))

(defn get-cp-recover-cooldown [] (game-config/get-cp-recover-cooldown))
(defn get-cp-recover-speed [] (game-config/get-cp-recover-speed))
(defn get-overload-recover-cooldown [] (game-config/get-overload-recover-cooldown))
(defn get-overload-recover-speed [] (game-config/get-overload-recover-speed))
(defn get-init-cp-list [] (game-config/get-init-cp-list))
(defn get-add-cp-list [] (game-config/get-add-cp-list))
(defn get-init-overload-list [] (game-config/get-init-overload-list))
(defn get-add-overload-list [] (game-config/get-add-overload-list))
(defn get-init-cp [level] (game-config/get-init-cp level))
(defn get-add-cp [level] (game-config/get-add-cp level))
(defn get-init-overload [level] (game-config/get-init-overload level))
(defn get-add-overload [level] (game-config/get-add-overload level))

(defn get-damage-scale [] (game-config/get-damage-scale))

(defn provider-map
  []
  (game-config/provider-map))
