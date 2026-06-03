;; Platform/version dispatch primitives used to isolate loader adapters.
(ns cn.li.mcmod.platform.dispatch
  "Dynamic platform/version selector shared by platform adapters.
  Loader-specific entrypoints set this var during mod bootstrap so mcmod multimethods
   can dispatch without depending on any specific loader namespace."
  (:require [cn.li.mcmod.platform.runtime :as prt]))

(def ^:private ^:dynamic *platform-version*
  "Current platform version keyword (e.g. :forge-1.20.1)."
  nil)

(defn install-platform-version!
  [platform-key label]
  (prt/install-impl! #'*platform-version* platform-key (or label "platform-version"))
  nil)

(defn current-platform-version
  []
  *platform-version*)

(defn call-with-platform-version
  [platform-key f]
  (binding [*platform-version* platform-key] (f)))

