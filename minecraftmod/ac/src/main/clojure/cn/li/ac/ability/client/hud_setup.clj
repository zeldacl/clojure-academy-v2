(ns cn.li.ac.ability.client.hud-setup
  "Compatibility facade for extracted ability client UI hooks."
  (:require [cn.li.ac.ability.platform-bridge.client-ui-hooks :as client-ui-hooks]))

(def build-client-overlay-plan client-ui-hooks/build-client-overlay-plan)
(def register-client-push-handlers! client-ui-hooks/register-client-push-handlers!)
(def runtime-client-ui-hooks client-ui-hooks/runtime-client-ui-hooks)
