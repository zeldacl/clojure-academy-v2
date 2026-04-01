(ns cn.li.ac.content.blocks.wireless
    "Content entrypoint for wireless blocks.

    Loads all wireless block namespaces. Each namespace self-registers its
    capabilities via declare-capability! and its tiles via deftile at load time.
    The wireless system (cn.li.ac.wireless.*) has no compile-time dependency on
    these namespaces; blocks interact with the wireless system purely through
    the acapi Java interfaces."
    (:require [cn.li.ac.wireless.shared.message-registry :as msg-reg]
                        ;; Block implementations — each registers capabilities at namespace load time.
                        [cn.li.ac.block.wireless-matrix.block]
                        [cn.li.ac.block.wireless-matrix.gui]
                        [cn.li.ac.block.wireless-node.block]
                        [cn.li.ac.block.wireless-node.gui]
                        [cn.li.ac.block.solar-gen.block]
                        [cn.li.ac.block.solar-gen.gui]))

(msg-reg/register-all!)
