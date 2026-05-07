(ns cn.li.ac.content.blocks.ability
  "Content entrypoint for ability system blocks"
  (:require [cn.li.ac.wireless.shared.message-registry :as msg-reg]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]))

(defn- load-ability-blocks! []
  (doseq [ns-sym '[cn.li.ac.block.developer.block
                   cn.li.ac.block.developer.gui
                   cn.li.ac.block.ability-interferer.block
                   cn.li.ac.block.ability-interferer.gui]]
    (require ns-sym)))

(defn- init-ability-block-definitions! []
  (doseq [init-sym '[cn.li.ac.block.developer.block/init-developer!
                    cn.li.ac.block.developer.gui/init-developer-gui!
                    cn.li.ac.block.ability-interferer.block/init-ability-interferer!
                    cn.li.ac.block.ability-interferer.gui/init-ability-interferer-gui!]]
    (when-let [init-fn (requiring-resolve init-sym)]
      (init-fn))))

(defonce-guard ability-blocks-installed?)

(defn init-ability-blocks!
  []
  (with-init-guard ability-blocks-installed?
    (load-ability-blocks!)
    (init-ability-block-definitions!)
    (msg-reg/register-all!)))
