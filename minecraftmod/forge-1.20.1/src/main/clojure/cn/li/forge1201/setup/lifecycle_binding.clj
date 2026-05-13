(ns cn.li.forge1201.setup.lifecycle-binding
  "Forge lifecycle listener binding extracted from mod-bus orchestration."
  (:require [cn.li.forge1201.setup.event-registration :as event-registration])
  (:import [net.minecraftforge.eventbus.api IEventBus]))

(defn register-lifecycle-phase!
  [^IEventBus mod-bus opts]
  (event-registration/register-lifecycle-phase! mod-bus opts)
  nil)
