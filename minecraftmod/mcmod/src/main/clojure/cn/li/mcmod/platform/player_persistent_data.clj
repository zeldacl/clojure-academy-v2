(ns cn.li.mcmod.platform.player-persistent-data
  "Platform-injected per-player persistent NBT (Forge getPersistentData / Fabric mixin)."
  (:require [cn.li.mcmod.platform.runtime :as prt]))

(def ^:private ^:dynamic *player-persistent-data-fn* nil)

(defn install-player-persistent-data!
  [get-persistent-data-fn label]
  (prt/install-impl! #'*player-persistent-data-fn* get-persistent-data-fn (or label "player-persistent-data")))

(defn get-persistent-data!
  [player]
  (when-let [f (prt/impl-current #'*player-persistent-data-fn*)]
    (f player)))

(defn persistent-data-available?
  []
  (prt/impl-available? #'*player-persistent-data-fn*))
