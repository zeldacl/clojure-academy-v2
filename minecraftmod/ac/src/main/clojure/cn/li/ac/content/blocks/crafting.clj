(ns cn.li.ac.content.blocks.crafting
  "Content entrypoint for crafting/processing blocks"
  (:require [cn.li.ac.content.block-loader-core :as block-loader]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]))

(def ^:private crafting-block-spec
  {:label :crafting
   :init-entries '[cn.li.ac.block.imag-fusor.block/init-imag-fusor!
                   cn.li.ac.block.metal-former.block/init-metal-former!
                   cn.li.ac.block.imag-fusor.gui/init-imag-fusor-gui!
                   cn.li.ac.block.metal-former.gui/init-metal-former-gui!]})

(defonce-guard crafting-blocks-installed?)

(defn init-crafting-blocks!
  []
  (with-init-guard crafting-blocks-installed?
    (block-loader/load-block-category! crafting-block-spec)))
