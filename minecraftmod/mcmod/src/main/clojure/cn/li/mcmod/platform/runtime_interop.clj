(ns cn.li.mcmod.platform.runtime-interop
  "Canonical runtime-side platform interop for world/player queries."
  (:require [cn.li.mcmod.platform.runtime :as prt]))

(defprotocol IRuntimeInterop
  (get-player-view [this player-uuid])
  (get-player-main-hand-item [this player-uuid])
  (get-block-entity-at [this world-id x y z]))

(def ^:private ^:dynamic *runtime* nil)

(defn install-runtime-interop!
  [impl label]
  (prt/install-impl! #'*runtime* impl (or label "runtime-interop")))

(defn available? [] (prt/impl-available? #'*runtime*))
(defn current [] (prt/impl-current #'*runtime*))
(defn call-with-runtime [rt f] (binding [*runtime* rt] (f)))

(prt/def-impl-wrappers '*runtime* IRuntimeInterop
  [get-player-view* get-player-view player-uuid]
  [get-player-main-hand-item* get-player-main-hand-item player-uuid]
  [get-block-entity-at* get-block-entity-at world-id x y z])
