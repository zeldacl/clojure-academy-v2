;; Platform/version dispatch primitives used to isolate loader adapters.
(ns cn.li.mcmod.platform.dispatch
  "Dynamic platform/version selector shared by platform adapters.
   Loader-specific entrypoints set this var during mod-init so mcmod multimethods
   can dispatch without depending on any specific loader namespace.")

(def ^:dynamic *platform-version*
  "Current platform version keyword (e.g. :forge-1.20.1)."
  nil)

