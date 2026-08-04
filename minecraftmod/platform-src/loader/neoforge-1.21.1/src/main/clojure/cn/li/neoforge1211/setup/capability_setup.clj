(ns cn.li.neoforge1211.setup.capability-setup
  "NeoForge capability + network payload registration extracted from mod-bus orchestration."
  (:require [cn.li.neoforge1211.setup.capability-wiring :as capability-wiring]
            [cn.li.neoforge1211.setup.imc-dispatcher :as imc-dispatcher])
  (:import [cn.li.neoforge1211.network ClojureNetwork]
           [net.neoforged.bus.api IEventBus]))

(defn register-capability-phase!
  [^IEventBus mod-bus _opts]
  (imc-dispatcher/register-imc-listener! mod-bus)
  (capability-wiring/register-capability-listener! mod-bus)
  (ClojureNetwork/register mod-bus)
  nil)
