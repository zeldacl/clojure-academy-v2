(ns cn.li.ac.block.imag-phase.block
  "Imaginary phase fluid block port for 1.20."
  (:require [cn.li.ac.config.modid :as modid]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.ac.block.imag-phase.handlers :as imag-phase-handlers]
            [cn.li.mcmod.block.dsl :as bdsl]
            [cn.li.mcmod.fluid.dsl :as fdsl]
            [cn.li.mcmod.util.log :as log]))

(defonce-guard imag-phase-installed?)

(defn init-imag-phase!
  []
  (with-init-guard imag-phase-installed?
    (fdsl/register-fluid!
      (fdsl/create-fluid-spec
        "imag-phase"
        {:registry-name "imag_phase"
         :physical {:luminosity 8
                    :density 7000
                    :viscosity 6000
                    :temperature 0
                    :can-convert-to-source false
                    :supports-boat false}
         :rendering {:still-texture (modid/asset-path "block" "phase_liquid")
                     :flowing-texture (modid/asset-path "block" "phase_liquid")
                     :overlay-texture (modid/asset-path "block" "black")
                     :tint-color -1
                     :is-translucent true}
         :behavior {:slope-find-distance 3
                    :level-decrease-per-block 1
                    :tick-rate 8
                    :explosion-resistance 100.0}
         :block {:block-id "imag-phase"
                 :registry-name "imag_phase"
                 :has-bucket? true
                 :bucket-registry-name "imag_phase_bucket"
                 :bucket-item-id "imag-phase-bucket"}}))
    ;; Keep a block-spec entry so existing metadata/event dispatch can route interactions.
    ;; Forge registration swaps this block-id to a LiquidBlock when fluid metadata exists.
    (bdsl/register-block!
      (bdsl/create-block-spec
        "imag-phase"
        {:registry-name "imag_phase"
         :physical {:material :stone
                    :hardness 100.0
                    :resistance 100.0
                    :requires-tool false
                    :sounds :stone}
         :rendering {:model-parent "minecraft:block/cube_all"
                     :textures {:all (modid/asset-path "block" "phase_liquid")}
                     :has-item-form? false}
            :events {:on-right-click imag-phase-handlers/handle-imag-phase-click}}))
    (log/info "Initialized Imag Phase fluid block")))

