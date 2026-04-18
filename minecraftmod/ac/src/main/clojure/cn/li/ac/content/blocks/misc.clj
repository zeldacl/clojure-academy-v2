(ns cn.li.ac.content.blocks.misc
  "Content entrypoint for miscellaneous blocks"
  (:require [cn.li.ac.wireless.shared.message-registry :as msg-reg]
            [cn.li.ac.block.ores :as ores]
            [cn.li.ac.block.imag-phase.block :as imag-phase]))

(defn- load-misc-blocks! []
  (doseq [ns-sym '[cn.li.ac.block.ores
                   cn.li.ac.block.imag-phase.block]]
    (require ns-sym))
  (ores/init-ores!)
  (imag-phase/init-imag-phase!))

(defonce ^:private misc-blocks-installed? (atom false))

(defn init-misc-blocks!
  []
  (when (compare-and-set! misc-blocks-installed? false true)
    (load-misc-blocks!)
    (msg-reg/register-all!)))
