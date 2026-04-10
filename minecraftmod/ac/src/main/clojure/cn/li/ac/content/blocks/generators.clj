(ns cn.li.ac.content.blocks.generators
  "Content entrypoint for energy generator blocks"
  (:require [cn.li.ac.wireless.shared.message-registry :as msg-reg]))

(defn- load-generator-blocks! []
  (doseq [ns-sym '[cn.li.ac.block.solar-gen.block
                   cn.li.ac.block.solar-gen.gui
                   cn.li.ac.block.wind-gen.block
                   cn.li.ac.block.wind-gen.gui
                   cn.li.ac.block.phase-gen.block
                   cn.li.ac.block.phase-gen.gui
                   cn.li.ac.block.cat-engine.block]]
    (require ns-sym)))

(defn- init-generator-block-definitions! []
  (doseq [init-sym '[cn.li.ac.block.solar-gen.block/init-solar-gen!
                    cn.li.ac.block.solar-gen.gui/init-solar-gui!
                    cn.li.ac.block.wind-gen.block/init-wind-gen!
                    cn.li.ac.block.wind-gen.gui/init-wind-gen-gui!
                    cn.li.ac.block.phase-gen.block/init-phase-gen!
                    cn.li.ac.block.phase-gen.gui/init-phase-gen-gui!
                    cn.li.ac.block.cat-engine.block/init-cat-engine!]]
    (when-let [init-fn (requiring-resolve init-sym)]
      (init-fn))))

(defonce ^:private generator-blocks-installed? (atom false))

(defn init-generator-blocks!
  []
  (when (compare-and-set! generator-blocks-installed? false true)
    (load-generator-blocks!)
    (init-generator-block-definitions!)
    (msg-reg/register-all!)))
