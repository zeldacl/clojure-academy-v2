(ns cn.li.mcmod.platform.world-effects
  "Protocol for world-level effects (lightning, explosions, entity/block queries).

  Platform installs via install-world-effects!. Content uses *-suffixed wrappers."
  (:require [cn.li.mcmod.platform.runtime :as prt]))

(defprotocol IWorldEffects
  "World-level effects and queries."

  (spawn-lightning! [this world-id x y z])
  (create-explosion! [this world-id x y z radius fire?])
  (spawn-projectile! [this world-id projectile-spec])
  (find-entities-in-radius [this world-id x y z radius])
  (find-entities-in-aabb [this world-id min-x min-y min-z max-x max-y max-z])
  (find-blocks-in-radius [this world-id x y z radius block-predicate])
  (play-sound! [this world-id x y z sound-id source volume pitch]))

(def ^:private ^:dynamic *runtime* nil)

(defn install-world-effects!
  [impl label]
  (prt/install-impl! #'*runtime* impl (or label "world-effects")))

(defn available? [] (prt/impl-available? #'*runtime*))
(defn current [] (prt/impl-current #'*runtime*))
(defn call-with-runtime [rt f] (binding [*runtime* rt] (f)))

(prt/def-impl-wrappers '*runtime* IWorldEffects
  [spawn-lightning!* spawn-lightning! world-id x y z]
  [create-explosion!* create-explosion! world-id x y z radius fire?]
  [spawn-projectile!* spawn-projectile! world-id projectile-spec]
  [find-entities-in-radius* find-entities-in-radius world-id x y z radius]
  [find-entities-in-aabb* find-entities-in-aabb world-id min-x min-y min-z max-x max-y max-z]
  [find-blocks-in-radius* find-blocks-in-radius world-id x y z radius block-predicate]
  [play-sound!* play-sound! world-id x y z sound-id source volume pitch])
