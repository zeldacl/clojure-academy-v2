(ns cn.li.mc1201.runtime.event.safe-handler
  "Small shared safety wrapper for Minecraft runtime event handlers."
  (:require [cn.li.mcmod.util.log :as log]))

(defn invoke
  "Run `f`, logging `label` and returning `default-result` on handler errors."
  [label default-result f]
  (try
    (f)
    (catch Exception e
      (log/error (str "Error handling " label) e)
      (log/stacktrace (str "Error handling " label " - full trace") e)
      default-result)))