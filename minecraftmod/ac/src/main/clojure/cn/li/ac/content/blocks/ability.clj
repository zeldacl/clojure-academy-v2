(ns cn.li.ac.content.blocks.ability
  "Content entrypoint for ability system blocks"
  (:require [cn.li.ac.content.block-loader-core :as block-loader]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]))

(def ^:private ability-block-spec
  {:label :ability
   ;; NOTE: developer.gui stays on the OLD CGUI path — panel-reactive.clj's
   ;; create-screen has a different signature (map arg, no :type tag) and
   ;; the right-panel skill-tree/console dispatch is still a stub. Do not
   ;; swap until panel-reactive.clj is a full drop-in replacement.
   ;; ability-interferer IS fully migrated (see generators.clj rationale).
   :namespaces '[cn.li.ac.block.developer.block
                 cn.li.ac.block.developer.gui
                 cn.li.ac.block.ability-interferer.block
                 cn.li.ac.block.ability-interferer.gui-reactive]
   :init-entries '[cn.li.ac.block.developer.block/init-developer!
                   cn.li.ac.block.developer.gui/init-developer-gui!
                   cn.li.ac.block.ability-interferer.block/init-ability-interferer!
                   cn.li.ac.block.ability-interferer.gui-reactive/init-ability-interferer-reactive!]})

(defonce-guard ability-blocks-installed?)

(defn init-ability-blocks!
  []
  (with-init-guard ability-blocks-installed?
    (block-loader/load-block-category! ability-block-spec)))
