(ns cn.li.ac.block.wireless-node.node-info-reactive
  "Typed network actions used by the Wireless Node Presentation ViewModel."
  (:require [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.mcmod.gui.container.action-payload :as action-payload]
            [cn.li.ac.gui.info-area :as info-area]))

(def ^:private gui-type :node)
(defn- msg [action] (msg-registry/msg gui-type action))

(defn- node-info-area-policy
  "Pure owner policy for the two editable node fields."
  [is-owner?]
  {:editable-node-name? (boolean is-owner?)
   :editable-password? (boolean is-owner?)})

(defn- send-owner [] (runtime-hooks/default-client-owner))

(defn send-change-name [container value]
  (net-client/send-to-server (send-owner) (msg :change-name)
    (action-payload/action-payload container {:node-name (str value)}) nil))

(defn send-change-password [container value]
  (net-client/send-to-server (send-owner) (msg :change-password)
    (action-payload/action-payload container {:password (str value)}) nil))

(defn info-area-snapshot
  "Build the declarative AC InfoArea projection for a node."
  [data is-owner?]
  (info-area/snapshot data {:owner? is-owner?}))

(defn attach!
  ([model initial]
   (info-area/attach! model initial))
  ([container data is-owner?]
   (let [slot (:presentation-info-area container)
         model (info-area/attach! (when slot @slot)
                                  (info-area-snapshot data is-owner?))]
     (when slot (reset! slot model))
     model)))

(defn rebuild!
  ([model next-snapshot]
   (info-area/rebuild! model next-snapshot))
  ([container data is-owner?]
   (let [slot (:presentation-info-area container)
         model (when slot @slot)
         next-snapshot (info-area-snapshot data is-owner?)
         model (info-area/rebuild! model next-snapshot)]
     (when slot (reset! slot model))
     model)))
