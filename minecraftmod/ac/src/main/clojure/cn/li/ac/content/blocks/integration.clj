(ns cn.li.ac.content.blocks.integration
  "Integration blocks content loader - energy converters and external mod support"
  (:require [cn.li.ac.block.energy-converter.block]
            [cn.li.ac.block.energy-converter.gui]
            [cn.li.ac.block.energy-converter-advanced.block]
            [cn.li.ac.block.energy-converter-advanced.gui]
            [cn.li.ac.block.energy-converter-elite.block]
            [cn.li.ac.block.energy-converter-elite.gui]
            [cn.li.ac.wireless.gui.message.registry :as msg-reg]
            [cn.li.mcmod.util.log :as log]))

;; Register all network messages for integration blocks
(msg-reg/register-all!)

(log/info "Loaded integration blocks content")
