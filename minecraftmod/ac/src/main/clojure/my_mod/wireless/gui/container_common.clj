(ns my-mod.wireless.gui.container-common
  "Shared helpers for wireless GUI containers"
  (:require [my-mod.wireless.gui.container-helpers :as helpers]))

(defn get-slot-item
  "Get item from slot"
  [container slot-index]
  (helpers/get-slot-item container slot-index))

(defn set-slot-item!
  "Set item in slot"
  [container slot-index item-stack]
  (helpers/set-slot-item! container slot-index item-stack))

(defn still-valid?
  "Check if container is still valid for player"
  [container player]
  (helpers/still-valid? container player))

(defn reset-container-atoms!
  "Reset container atoms to defaults"
  [& pairs]
  (apply helpers/reset-container-atoms! pairs))
