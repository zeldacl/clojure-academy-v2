(ns cn.li.mcmod.runtime.client-render-provider
  (:require [cn.li.mcmod.client.input-buttons :as input]))

(defn runtime-provider [_]
  {:initial-button-state input/initial-button-state
   :handle-button-state! input/handle-button-state!})
