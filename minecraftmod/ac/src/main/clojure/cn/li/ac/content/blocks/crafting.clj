(ns cn.li.ac.content.blocks.crafting
  "Content entrypoint for crafting/processing blocks"
  (:require [cn.li.ac.wireless.shared.message-registry :as msg-reg]
            ;; Crafting block implementations
            [cn.li.ac.block.imag-fusor.block]
            [cn.li.ac.block.imag-fusor.gui]
            [cn.li.ac.block.metal-former.block]
            [cn.li.ac.block.metal-former.gui]))

(msg-reg/register-all!)
