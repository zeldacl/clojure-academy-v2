(ns cn.li.forge1201.setup.lifecycle-binding
  "Forge lifecycle listener binding extracted from mod-bus orchestration."
  (:require [cn.li.forge1201.integration.side :as side]
            [cn.li.forge1201.setup.lifecycle-listeners :as lifecycle-listeners])
  (:import [net.minecraftforge.eventbus.api IEventBus]))

(defn register-lifecycle-phase!
  [^IEventBus mod-bus {:keys [on-common-setup on-client-setup]}]
  (lifecycle-listeners/register-common-lifecycle-listeners! mod-bus on-common-setup on-client-setup)
  (when (side/client-side?)
    (lifecycle-listeners/register-client-key-mappings! mod-bus))
  nil)
