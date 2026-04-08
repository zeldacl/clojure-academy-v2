(ns cn.li.ac.content.blocks.crafting
  "Content entrypoint for crafting/processing blocks"
  (:require [cn.li.ac.wireless.shared.message-registry :as msg-reg]))

(defn- load-crafting-blocks! []
  (doseq [ns-sym '[cn.li.ac.block.imag-fusor.block
                   cn.li.ac.block.imag-fusor.gui
                   cn.li.ac.block.metal-former.block
                   cn.li.ac.block.metal-former.gui]]
    (require ns-sym)))

(when (not= "true" (System/getProperty "ac.check.clojure"))
  (load-crafting-blocks!)
  (msg-reg/register-all!))
