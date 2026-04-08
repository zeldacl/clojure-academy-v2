(ns cn.li.ac.content.blocks.misc
  "Content entrypoint for miscellaneous blocks"
  (:require [cn.li.ac.wireless.shared.message-registry :as msg-reg]))

(defn- load-misc-blocks! []
  (doseq [ns-sym '[cn.li.ac.block.ores]]
    (require ns-sym)))

(when (not= "true" (System/getProperty "ac.check.clojure"))
  (load-misc-blocks!)
  (msg-reg/register-all!))
