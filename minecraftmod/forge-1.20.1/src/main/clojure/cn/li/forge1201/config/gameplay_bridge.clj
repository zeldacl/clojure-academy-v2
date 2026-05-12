(ns cn.li.forge1201.config.gameplay-bridge
  "Compatibility facade; gameplay accessors moved to cn.li.forge1201.config.bridge."
  (:require [cn.li.forge1201.config.bridge :as bridge]))

(defn analysis-enabled? [] (bridge/analysis-enabled?))
(defn attack-player? [] (bridge/attack-player?))
(defn destroy-blocks? [] (bridge/destroy-blocks?))
(defn gen-ores? [] (bridge/gen-ores?))
(defn gen-phase-liquid? [] (bridge/gen-phase-liquid?))
(defn heads-or-tails? [] (bridge/heads-or-tails?))

(defn get-normal-metal-blocks [] (bridge/get-normal-metal-blocks))
(defn get-weak-metal-blocks [] (bridge/get-weak-metal-blocks))
(defn get-metal-entities [] (bridge/get-metal-entities))
(defn is-metal-block? [block-id] (bridge/is-metal-block? block-id))
(defn is-normal-metal-block? [block-id] (bridge/is-normal-metal-block? block-id))
(defn is-weak-metal-block? [block-id] (bridge/is-weak-metal-block? block-id))
(defn is-metal-entity? [entity-id] (bridge/is-metal-entity? entity-id))

(defn get-cp-recover-cooldown [] (bridge/get-cp-recover-cooldown))
(defn get-cp-recover-speed [] (bridge/get-cp-recover-speed))
(defn get-overload-recover-cooldown [] (bridge/get-overload-recover-cooldown))
(defn get-overload-recover-speed [] (bridge/get-overload-recover-speed))
(defn get-init-cp-list [] (bridge/get-init-cp-list))
(defn get-add-cp-list [] (bridge/get-add-cp-list))
(defn get-init-overload-list [] (bridge/get-init-overload-list))
(defn get-add-overload-list [] (bridge/get-add-overload-list))
(defn get-init-cp [level] (bridge/get-init-cp level))
(defn get-add-cp [level] (bridge/get-add-cp level))
(defn get-init-overload [level] (bridge/get-init-overload level))
(defn get-add-overload [level] (bridge/get-add-overload level))

(defn get-damage-scale [] (bridge/get-damage-scale))

(defn provider-map
  []
  (bridge/provider-map))
