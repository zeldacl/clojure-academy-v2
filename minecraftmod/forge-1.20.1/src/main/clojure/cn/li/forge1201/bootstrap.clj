(ns cn.li.forge1201.bootstrap
  "Shared bootstrap helper utilities for Forge 1.20.1")

(defn invoke-bootstrap-helper
  [method-name & args]
  (clojure.lang.Reflector/invokeStaticMethod
    "cn.li.forge1201.shim.ForgeBootstrapHelper"
    ^String method-name
    (to-array args)))
