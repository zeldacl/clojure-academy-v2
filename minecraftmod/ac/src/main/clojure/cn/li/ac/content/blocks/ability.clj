(ns cn.li.ac.content.blocks.ability
  "Content entrypoint for ability system blocks"
  (:require [cn.li.ac.wireless.shared.message-registry :as msg-reg]
            ;; Ability block implementations
            [cn.li.ac.block.developer.block]
            [cn.li.ac.block.developer.gui]
            [cn.li.ac.block.ability-interferer.block]
            [cn.li.ac.block.ability-interferer.gui]))

(msg-reg/register-all!)
