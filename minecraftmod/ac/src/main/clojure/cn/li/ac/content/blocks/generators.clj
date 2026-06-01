(ns cn.li.ac.content.blocks.generators
  "Content entrypoint for energy generator blocks"
  (:require [cn.li.ac.content.block-loader-core :as block-loader]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]))

(def ^:private generator-block-spec
  {:label :generators
   :namespaces '[cn.li.ac.block.solar-gen.block
                 cn.li.ac.block.solar-gen.gui
                 cn.li.ac.block.wind-gen.block
                 cn.li.ac.block.wind-gen.gui
                 cn.li.ac.block.phase-gen.block
                 cn.li.ac.block.phase-gen.gui
                 cn.li.ac.block.cat-engine.block]
   :init-entries '[cn.li.ac.block.solar-gen.block/init-solar-gen!
                   cn.li.ac.block.solar-gen.gui/init-solar-gui!
                   cn.li.ac.block.wind-gen.block/init-wind-gen!
                   cn.li.ac.block.wind-gen.gui/init-wind-gen-gui!
                   cn.li.ac.block.phase-gen.block/init-phase-gen!
                   cn.li.ac.block.phase-gen.gui/init-phase-gen-gui!
                   cn.li.ac.block.cat-engine.block/init-cat-engine!]})

(defonce-guard generator-blocks-installed?)

(defn init-generator-blocks!
  []
  (with-init-guard generator-blocks-installed?
    (block-loader/load-block-category! generator-block-spec)))
