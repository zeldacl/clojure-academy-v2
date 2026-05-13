(ns cn.li.ac.ability.server.runtime-setup
  "Compatibility facade for extracted ability server runtime hooks."
  (:require [cn.li.ac.ability.platform-bridge.server-hooks :as server-hooks]))

(def install-store! server-hooks/install-store!)
(def register-lifecycle-subscriptions! server-hooks/register-lifecycle-subscriptions!)
(def runtime-server-hooks server-hooks/runtime-server-hooks)
