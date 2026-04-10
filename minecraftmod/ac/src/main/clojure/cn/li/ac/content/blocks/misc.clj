(ns cn.li.ac.content.blocks.misc
  "Content entrypoint for miscellaneous blocks"
  (:require [cn.li.ac.wireless.shared.message-registry :as msg-reg]
            [cn.li.ac.block.ores :as ores]))

(defn- load-misc-blocks! []
  (doseq [ns-sym '[cn.li.ac.block.ores]]
    (require ns-sym))
  (ores/init-ores!))

(defonce ^:private misc-blocks-installed? (atom false))

(defn init-misc-blocks!
  []
  (when (compare-and-set! misc-blocks-installed? false true)
    (load-misc-blocks!)
    (msg-reg/register-all!)))
