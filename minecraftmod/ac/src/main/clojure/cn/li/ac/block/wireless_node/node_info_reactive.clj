(ns cn.li.ac.block.wireless-node.node-info-reactive
  "Typed network actions used by the Wireless Node Presentation ViewModel."
  (:require [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.mcmod.gui.container.action-payload :as action-payload]))

(def ^:private gui-type :node)
(defn- msg [action] (msg-registry/msg gui-type action))
(defn- send-owner [] (runtime-hooks/default-client-owner))

(defn send-change-name [container value]
  (net-client/send-to-server (send-owner) (msg :change-name)
    (action-payload/action-payload container {:value (str value)}) nil))

(defn send-change-password [container value]
  (net-client/send-to-server (send-owner) (msg :change-password)
    (action-payload/action-payload container {:value (str value)}) nil))

(defn attach! [& _] nil)
(defn rebuild! [& _] nil)
