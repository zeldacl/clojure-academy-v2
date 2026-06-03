(ns cn.li.mcmod.platform.block-manipulation
  "Protocol for breaking and modifying blocks."
  (:require [cn.li.mcmod.platform.runtime :as prt]))

(defprotocol IBlockManipulation
  (break-block! [this player-id world-id x y z drop?]
    [this player-id world-id x y z drop? fortune-level])
  (set-block! [this world-id x y z block-id])
  (get-block [this world-id x y z])
  (get-block-hardness [this world-id x y z])
  (can-break-block? [this player-id world-id x y z])
  (find-blocks-in-line [this world-id x1 y1 z1 dx dy dz max-distance])
  (liquid-block? [this world-id x y z])
  (farmland-block? [this world-id x y z]))

(def ^:private ^:dynamic *runtime* nil)

(defn install-block-manipulation!
  [impl label]
  (prt/install-impl! #'*runtime* impl (or label "block-manipulation")))

(defn available? [] (prt/impl-available? #'*runtime*))
(defn current [] (prt/impl-current #'*runtime*))
(defn call-with-runtime [rt f] (binding [*runtime* rt] (f)))

(prt/def-impl-wrappers '*runtime* IBlockManipulation
  [break-block!* break-block! player-id world-id x y z drop?]
  [set-block!* set-block! world-id x y z block-id]
  [get-block* get-block world-id x y z]
  [get-block-hardness* get-block-hardness world-id x y z]
  [can-break-block?* can-break-block? player-id world-id x y z]
  [find-blocks-in-line* find-blocks-in-line world-id x1 y1 z1 dx dy dz max-distance]
  [liquid-block?* liquid-block? world-id x y z]
  [farmland-block?* farmland-block? world-id x y z])

(defn break-block-with-fortune!*
  [player-id world-id x y z drop? fortune-level]
  (when-let [rt *runtime*]
    (break-block! rt player-id world-id x y z drop? fortune-level)))
