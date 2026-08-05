(ns cn.li.neoforge262.setup.capability-setup
  "NeoForge capability + network payload registration extracted from mod-bus orchestration."
  (:require [cn.li.neoforge262.setup.capability-wiring :as capability-wiring])
  (:import [cn.li.neoforge262.network ClojureNetwork]
           [net.neoforged.bus.api IEventBus]))

(defn register-capability-phase!
  [^IEventBus mod-bus _opts]
  (capability-wiring/register-capability-listener! mod-bus)
  (ClojureNetwork/register mod-bus)
  nil)
