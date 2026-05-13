(ns cn.li.ac.content.blocks.ability
  "Content entrypoint for ability system blocks"
  (:require [cn.li.ac.content.block-loader-core :as block-loader]
            [cn.li.ac.wireless.shared.message-registry :as msg-reg]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]))

(def ^:private ability-block-spec
  {:label :ability
   :namespaces '[cn.li.ac.block.developer.block
                 cn.li.ac.block.developer.gui
                 cn.li.ac.block.ability-interferer.block
                 cn.li.ac.block.ability-interferer.gui]
   :init-entries '[cn.li.ac.block.developer.block/init-developer!
                   cn.li.ac.block.developer.gui/init-developer-gui!
                   cn.li.ac.block.ability-interferer.block/init-ability-interferer!
                   cn.li.ac.block.ability-interferer.gui/init-ability-interferer-gui!]
   :post-init-entries [msg-reg/register-all!]})

(defonce-guard ability-blocks-installed?)

(defn init-ability-blocks!
  []
  (with-init-guard ability-blocks-installed?
    (block-loader/load-block-category! ability-block-spec)))
