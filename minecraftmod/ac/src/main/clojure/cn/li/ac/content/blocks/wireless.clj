(ns cn.li.ac.content.blocks.wireless
  "Content entrypoint for wireless blocks (matrix + node).

  Each block namespace registers via init-machine! during category init.
  The wireless system (cn.li.ac.wireless.*) has no compile-time dependency on
  these namespaces; blocks interact through acapi Java interfaces."
  (:require [cn.li.ac.content.block-loader-core :as block-loader]
            [cn.li.ac.wireless.shared.message-registry :as msg-reg]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]))

(def ^:private wireless-block-spec
  {:label :wireless
   :namespaces '[cn.li.ac.block.wireless-matrix.block
                cn.li.ac.block.wireless-matrix.gui
                cn.li.ac.block.wireless-node.block
                cn.li.ac.block.wireless-node.gui]
   :init-entries '[cn.li.ac.block.wireless-matrix.block/init-wireless-matrix!
                   cn.li.ac.block.wireless-matrix.gui/init-wireless-matrix-gui!
                   cn.li.ac.block.wireless-node.block/init-wireless-nodes!
                   cn.li.ac.block.wireless-node.gui/init-wireless-node-gui!]
   :post-init-entries [msg-reg/register-all!]})

(defonce-guard wireless-blocks-installed?)

(defn init-wireless-blocks!
  []
  (with-init-guard wireless-blocks-installed?
    (block-loader/load-block-category! wireless-block-spec)))
