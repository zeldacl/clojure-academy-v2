(ns cn.li.ac.content.entities.all
  "Content entrypoint for AC entity declarations."
  (:require [cn.li.mcmod.entity.dsl :as edsl]))

(defonce ^:private entities-installed? (atom false))

(defn init-entities!
  []
  (when (compare-and-set! entities-installed? false true)
    ;; Keep existing registry IDs stable for world/network compatibility.
    (edsl/register-entity!
      (edsl/create-entity-spec
        "entity_mag_hook"
        {:entity-kind :scripted-projectile
         :category :misc
         :width 0.5
         :height 0.5
         :client-tracking-range 64
         :update-interval 1
         :properties {:projectile {:default-item-id "my_mod:mag_hook"
                                   :gravity 0.05
                                   :damage 4.0
                                   :pickup-distance-sqr 2.25
                                   :drop-item-on-discard? true
                                   :hooks {:on-hit-block :anchor
                                           :on-hit-entity :damage-and-discard
                                           :on-anchored-tick :drop-when-invalid
                                           :on-anchored-hurt :discard-when-hurt}}}}))
    (edsl/register-entity!
      (edsl/create-entity-spec
        "intensify_effect"
        {:entity-kind :scripted-effect
         :category :misc
         :width 0.1
         :height 0.1
         :client-tracking-range 64
         :update-interval 1
         :properties {:effect {:life-ticks 15
                               :follow-owner? true
                               :hook :intensify-arcs
                               :hook-class "cn.li.forge1201.entity.effect.hooks.IntensifyArcsEffectHook"}}}))))
