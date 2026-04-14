(ns cn.li.ac.content.blocks.crafting
  "Content entrypoint for crafting/processing blocks"
  (:require [cn.li.ac.wireless.shared.message-registry :as msg-reg]))

(defn- load-crafting-blocks! []
  (doseq [init-sym '[cn.li.ac.block.imag-fusor.block/init-imag-fusor!
                    cn.li.ac.block.metal-former.block/init-metal-former!]]
    (when-let [init-fn (requiring-resolve init-sym)]
      (init-fn)))
  (doseq [gui-init-sym '[cn.li.ac.block.imag-fusor.gui/init-imag-fusor-gui!
                        cn.li.ac.block.metal-former.gui/init-metal-former-gui!]]
    (when-let [gui-init-fn (requiring-resolve gui-init-sym)]
      (gui-init-fn))))

(defonce ^:private crafting-blocks-installed? (atom false))

(defn init-crafting-blocks!
  []
  (when (compare-and-set! crafting-blocks-installed? false true)
    (load-crafting-blocks!)
    (msg-reg/register-all!)))
