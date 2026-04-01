(ns cn.li.ac.content.blocks.wireless
    "Content entrypoint for wireless blocks.

    This namespace is the new load target for wireless block content."
    (:require [cn.li.ac.wireless.shared.message-registry :as msg-reg]
                        [cn.li.ac.wireless.matrix.block]
                        [cn.li.ac.wireless.matrix.gui]
                        [cn.li.ac.wireless.node.block]
                        [cn.li.ac.wireless.node.gui]
                        [cn.li.ac.wireless.solar.block]
                        [cn.li.ac.wireless.solar.gui]))

(msg-reg/register-all!)
