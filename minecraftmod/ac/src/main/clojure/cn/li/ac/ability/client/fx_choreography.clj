(ns cn.li.ac.ability.client.fx-choreography
  "Compatibility facade for extracted ability client effect hooks."
  (:require [cn.li.ac.ability.platform-bridge.client-effect-hooks :as client-effect-hooks]))

(def runtime-client-effect-hooks client-effect-hooks/runtime-client-effect-hooks)
