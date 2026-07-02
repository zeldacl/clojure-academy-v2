(ns cn.li.mcmod.platform.gui-open
  "Platform-injected player menu open (Forge openMenu / Fabric openHandledScreen).

  Function stored in Framework [:platform :gui-open] instead of ^:dynamic var."
  (:require [cn.li.mcmod.framework :as fw]))

(defn install-open-menu!
  "Install the open-menu function. Backward compatible with old (fn label) signature."
  ([open-menu-fn]
   (when-let [fw-atom (fw/fw-atom)]
     (swap! fw-atom assoc-in [:platform :gui-open] open-menu-fn))
   nil)
  ([open-menu-fn label]
   (install-open-menu! open-menu-fn)))

(defn open-player-menu!
  [player factory]
  (when-let [f (get-in @(fw/fw-atom) [:platform :gui-open])]
    (f player factory)))

(defn open-menu-available?
  []
  (boolean (get-in @(fw/fw-atom) [:platform :gui-open])))
