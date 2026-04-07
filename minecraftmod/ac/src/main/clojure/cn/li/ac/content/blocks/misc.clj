(ns cn.li.ac.content.blocks.misc
  "Content entrypoint for miscellaneous blocks"
  (:require [cn.li.ac.wireless.shared.message-registry :as msg-reg]
            ;; Misc block implementations
            [cn.li.ac.block.ores]))

(when (not= "true" (System/getProperty "ac.check.clojure"))
  (msg-reg/register-all!))
