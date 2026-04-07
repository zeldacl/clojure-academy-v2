(ns cn.li.ac.content.blocks.generators
  "Content entrypoint for energy generator blocks"
  (:require [cn.li.ac.wireless.shared.message-registry :as msg-reg]
            ;; Generator block implementations
            [cn.li.ac.block.solar-gen.block]
            [cn.li.ac.block.solar-gen.gui]
            [cn.li.ac.block.wind-gen.block]
            [cn.li.ac.block.wind-gen.gui]
            [cn.li.ac.block.phase-gen.block]
            [cn.li.ac.block.phase-gen.gui]
            [cn.li.ac.block.cat-engine.block]))

(when (not= "true" (System/getProperty "ac.check.clojure"))
  (msg-reg/register-all!))
