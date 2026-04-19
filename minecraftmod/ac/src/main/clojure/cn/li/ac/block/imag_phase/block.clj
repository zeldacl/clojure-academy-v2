(ns cn.li.ac.block.imag-phase.block
  "Imaginary phase fluid block port for 1.20."
  (:require [cn.li.ac.config.modid :as modid]
            [cn.li.ac.block.phase-gen.config :as phase-config]
            [cn.li.mcmod.block.dsl :as bdsl]
            [cn.li.mcmod.fluid.dsl :as fdsl]
            [cn.li.mcmod.platform.item :as pitem]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.mcmod.util.log :as log]))

(defonce ^:private imag-phase-installed? (atom false))

(defn- stack-empty?
  [stack]
  (or (nil? stack)
      (try
        (boolean (pitem/item-is-empty? stack))
        (catch Exception _
          false))))

(defn- stack-id
  [stack]
  (when-not (stack-empty? stack)
    (try
      (some-> stack pitem/item-get-item pitem/item-get-registry-name str)
      (catch Exception _
        nil))))

(defn- to-phase-liquid-matter-unit!
  [stack]
  (try
    (pitem/item-set-damage! stack phase-config/matter-unit-phase-liquid-meta)
    true
    (catch Exception _
      false)))

(defn- handle-imag-phase-click
  [{:keys [world item-stack] :as _ctx}]
  (when (and world
             (not (world/world-is-client-side* world))
             (not (stack-empty? item-stack))
             (= (stack-id item-stack) phase-config/matter-unit-item-id))
    (when (to-phase-liquid-matter-unit! item-stack)
      {:consume? true})))

(defn init-imag-phase!
  []
  (when (compare-and-set! imag-phase-installed? false true)
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
         :events {:on-right-click handle-imag-phase-click}}))
    (log/info "Initialized Imag Phase fluid block")))

