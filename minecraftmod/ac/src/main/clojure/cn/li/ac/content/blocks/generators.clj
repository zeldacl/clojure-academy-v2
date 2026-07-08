(ns cn.li.ac.content.blocks.generators
  "Content entrypoint for energy generator blocks"
  (:require [cn.li.ac.content.block-loader-core :as block-loader]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]))

(def ^:private generator-block-spec
  {:label :generators
   ;; Reactive UI migration: screen-fn now comes from *.gui-reactive namespaces
   ;; (cn.li.mcmod.ui.* signal framework). Old *.gui namespaces are kept as
   ;; reference/fallback and still loaded (some reactive files delegate
   ;; container/slot logic to them) but their init-*-gui! is no longer called,
   ;; so gui-reg/register-block-gui! only runs once with the reactive :screen-fn.
   :namespaces '[cn.li.ac.block.solar-gen.block
                 cn.li.ac.block.solar-gen.gui
                 cn.li.ac.block.solar-gen.gui-reactive
                 cn.li.ac.block.wind-gen.block
                 cn.li.ac.block.wind-gen.gui
                 cn.li.ac.block.wind-gen.gui-reactive
                 cn.li.ac.block.phase-gen.block
                 cn.li.ac.block.phase-gen.gui
                 cn.li.ac.block.phase-gen.gui-reactive
                 cn.li.ac.block.cat-engine.block]
   :init-entries '[cn.li.ac.block.solar-gen.block/init-solar-gen!
                   cn.li.ac.block.solar-gen.gui-reactive/init-solar-reactive!
                   cn.li.ac.block.wind-gen.block/init-wind-gen!
                   cn.li.ac.block.wind-gen.gui-reactive/init-wind-gen-reactive!
                   cn.li.ac.block.phase-gen.block/init-phase-gen!
                   cn.li.ac.block.phase-gen.gui-reactive/init-phase-gen-reactive!
                   cn.li.ac.block.cat-engine.block/init-cat-engine!]})

(defonce-guard generator-blocks-installed?)

(defn init-generator-blocks!
  []
  (with-init-guard generator-blocks-installed?
    (block-loader/load-block-category! generator-block-spec)))
