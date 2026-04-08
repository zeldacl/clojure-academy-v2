(ns cn.li.ac.content.blocks.generators
  "Content entrypoint for energy generator blocks"
  (:require [cn.li.ac.wireless.shared.message-registry :as msg-reg]))

(defn- load-generator-blocks! []
  (doseq [ns-sym '[cn.li.ac.block.solar-gen.block
                   cn.li.ac.block.solar-gen.gui
                   cn.li.ac.block.wind-gen.block
                   cn.li.ac.block.wind-gen.gui
                   cn.li.ac.block.phase-gen.block
                   cn.li.ac.block.phase-gen.gui
                   cn.li.ac.block.cat-engine.block]]
    (require ns-sym)))

(when (not= "true" (System/getProperty "ac.check.clojure"))
  (load-generator-blocks!)
  (msg-reg/register-all!))
