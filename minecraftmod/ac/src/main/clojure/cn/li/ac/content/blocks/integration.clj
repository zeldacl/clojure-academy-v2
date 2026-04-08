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

(when (not= "true" (System/getProperty "ac.check.clojure"))
  (load-integration-blocks!)
  (log/info "Loaded integration blocks content"))
