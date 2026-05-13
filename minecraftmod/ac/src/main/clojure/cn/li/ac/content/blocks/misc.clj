(ns cn.li.ac.content.blocks.misc
  "Content entrypoint for miscellaneous blocks"
  (:require [cn.li.ac.content.block-loader-core :as block-loader]
            [cn.li.ac.wireless.shared.message-registry :as msg-reg]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]))

(def ^:private misc-block-spec
  {:label :misc
   :namespaces '[cn.li.ac.block.ores
                 cn.li.ac.block.imag-phase.block]
   :init-entries '[cn.li.ac.block.ores/init-ores!
                   cn.li.ac.block.imag-phase.block/init-imag-phase!]
   :post-init-entries [msg-reg/register-all!]})

(defonce-guard misc-blocks-installed?)

(defn init-misc-blocks!
  []
  (with-init-guard misc-blocks-installed?
    (block-loader/load-block-category! misc-block-spec)))
