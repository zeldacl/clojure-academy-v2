(ns cn.li.ac.content.blocks.integration
  "Integration blocks content loader - energy converters and external mod support"
  (:require [cn.li.mcmod.util.log :as log]))

(defn- load-integration-blocks! []
  (doseq [ns-sym '[cn.li.ac.block.energy-converter.block
                   cn.li.ac.block.energy-converter.gui
                   cn.li.ac.block.energy-converter-advanced.block
                   cn.li.ac.block.energy-converter-advanced.gui
                   cn.li.ac.block.energy-converter-elite.block
                   cn.li.ac.block.energy-converter-elite.gui]]
    (require ns-sym)))

(defn- init-integration-block-definitions! []
  (doseq [init-sym '[cn.li.ac.block.energy-converter.block/init-energy-converter!
                    cn.li.ac.block.energy-converter.gui/init-energy-converter-gui!
                    cn.li.ac.block.energy-converter-advanced.block/init-energy-converter-advanced!
                    cn.li.ac.block.energy-converter-advanced.gui/init-energy-converter-advanced-gui!
                    cn.li.ac.block.energy-converter-elite.block/init-energy-converter-elite!
                    cn.li.ac.block.energy-converter-elite.gui/init-energy-converter-elite-gui!]]
    (when-let [init-fn (requiring-resolve init-sym)]
      (init-fn))))

(defonce ^:private integration-blocks-installed? (atom false))

(defn init-integration-blocks!
  []
  (when (compare-and-set! integration-blocks-installed? false true)
    (load-integration-blocks!)
    (init-integration-block-definitions!)
    (log/info "Loaded integration blocks content")))
