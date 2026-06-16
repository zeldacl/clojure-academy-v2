(ns cn.li.mcmod.platform.gui-open
  "Platform-injected player menu open (Forge openMenu / Fabric openHandledScreen)."
  (:require [cn.li.mcmod.platform.runtime :as prt]))

(def ^:private ^:dynamic *open-menu-fn* nil)

(defn install-open-menu!
  [open-menu-fn label]
  (prt/install-impl! #'*open-menu-fn* open-menu-fn (or label "open-menu")))

(defn open-player-menu!
  [player factory]
  (when-let [f (prt/impl-current #'*open-menu-fn*)]
    (f player factory)))

(defn open-menu-available?
  []
  (prt/impl-available? #'*open-menu-fn*))
