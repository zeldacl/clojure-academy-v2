(ns cn.li.ac.content.blocks.ability
  "Content entrypoint for ability system blocks"
  (:require [cn.li.ac.wireless.shared.message-registry :as msg-reg]))

(defn- load-ability-blocks! []
  (doseq [ns-sym '[cn.li.ac.block.developer.block
                   cn.li.ac.block.developer.gui
                   cn.li.ac.block.ability-interferer.block
                   cn.li.ac.block.ability-interferer.gui]]
    (require ns-sym)))

(when (not= "true" (System/getProperty "ac.check.clojure"))
  (load-ability-blocks!)
  (msg-reg/register-all!))
