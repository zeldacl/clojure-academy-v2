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
                               :hook :intensify-arcs}}}))

    ;; EntityCoinThrowing - for Railgun skill
    ;; Note: This is a simplified implementation using scripted-projectile
    ;; The original has special behavior (follows player XZ, returns to inventory)
    ;; which may require custom Java implementation for full fidelity
    (edsl/register-entity!
      (edsl/create-entity-spec
        "entity_coin_throwing"
        {:entity-kind :scripted-projectile
         :category :misc
         :width 0.2
         :height 0.2
         :client-tracking-range 64
         :update-interval 1
         :properties {:projectile {:default-item-id "my_mod:coin"
                                   :gravity 0.06
                                   :damage 0.0
                                   :pickup-distance-sqr 0.0  ; No pickup
                                   :drop-item-on-discard? false  ; Returns to inventory via skill logic
                                   :hooks {:on-hit-block :pass-through
                                           :on-hit-entity :pass-through
                                           :on-anchored-tick :none
                                           :on-anchored-hurt :none}}}}))

    ;; Ray family (Forge adapter shells + renderer-id dispatch).
    (edsl/register-entity!
      (edsl/create-entity-spec
        "entity_md_ray"
        {:entity-kind :scripted-ray
         :category :misc
         :width 0.2
         :height 0.2
         :client-tracking-range 96
         :update-interval 1
         :properties {:ray {:life-ticks 50
                            :length 15.0
                            :blend-in-ms 200.0
                            :blend-out-ms 700.0
                            :inner-width 0.17
                            :outer-width 0.22
                            :glow-width 1.5
                            :start-color 0xD8F8D8
                            :end-color 0x6AF26A
                            :renderer-id "ray-composite"
                            :hook :md-ray}}}))

    (edsl/register-entity!
      (edsl/create-entity-spec
        "entity_mine_ray_basic"
        {:entity-kind :scripted-ray
         :category :misc
         :width 0.2
         :height 0.2
         :client-tracking-range 96
         :update-interval 1
         :properties {:ray {:life-ticks 233333
                            :length 15.0
                            :blend-in-ms 200.0
                            :blend-out-ms 400.0
                            :inner-width 0.03
                            :outer-width 0.045
                            :glow-width 0.3
                            :start-color 0xD8F8D8
                            :end-color 0x6AF26A
                            :renderer-id "ray-composite"
                            :hook :mine-ray-basic}}}))

    (edsl/register-entity!
      (edsl/create-entity-spec
        "entity_mine_ray_expert"
        {:entity-kind :scripted-ray
         :category :misc
         :width 0.2
         :height 0.2
         :client-tracking-range 96
         :update-interval 1
         :properties {:ray {:life-ticks 233333
                            :length 15.0
                            :blend-in-ms 200.0
                            :blend-out-ms 400.0
                            :inner-width 0.045
                            :outer-width 0.056
                            :glow-width 0.5
                            :start-color 0xD8F8D8
                            :end-color 0x6AF26A
                            :renderer-id "ray-composite"
                            :hook :mine-ray-expert}}}))

    (edsl/register-entity!
      (edsl/create-entity-spec
        "entity_mine_ray_luck"
        {:entity-kind :scripted-ray
         :category :misc
         :width 0.2
         :height 0.2
         :client-tracking-range 96
         :update-interval 1
         :properties {:ray {:life-ticks 233333
                            :length 15.0
                            :blend-in-ms 100.0
                            :blend-out-ms 300.0
                            :inner-width 0.04
                            :outer-width 0.05
                            :glow-width 0.45
                            :start-color 0xF1E5F7
                            :end-color 0xCDA6E8
                            :renderer-id "ray-composite"
                            :hook :mine-ray-luck}}}))

    (edsl/register-entity!
      (edsl/create-entity-spec
        "entity_md_ray_small"
        {:entity-kind :scripted-ray
         :category :misc
         :width 0.2
         :height 0.2
         :client-tracking-range 96
         :update-interval 1
         :properties {:ray {:life-ticks 14
                            :length 15.0
                            :blend-in-ms 200.0
                            :blend-out-ms 400.0
                            :inner-width 0.03
                            :outer-width 0.045
                            :glow-width 0.3
                            :start-color 0xD8F8D8
                            :end-color 0x6AF26A
                            :renderer-id "ray-composite"
                            :hook :md-ray-small}}}))

    (edsl/register-entity!
      (edsl/create-entity-spec
        "entity_md_ray_barrage"
        {:entity-kind :scripted-ray
         :category :misc
         :width 0.2
         :height 0.2
         :client-tracking-range 96
         :update-interval 1
         :properties {:ray {:life-ticks 50
                            :length 15.0
                            :blend-in-ms 200.0
                            :blend-out-ms 700.0
                            :inner-width 0.17
                            :outer-width 0.22
                            :glow-width 1.5
                            :start-color 0xD8F8D8
                            :end-color 0x6AF26A
                            :renderer-id "ray-composite"
                            :hook :md-ray-barrage}}}))

    (edsl/register-entity!
      (edsl/create-entity-spec
        "entity_barrage_ray_pre"
        {:entity-kind :scripted-ray
         :category :misc
         :width 0.2
         :height 0.2
         :client-tracking-range 96
         :update-interval 1
         :properties {:ray {:life-ticks 30
                            :length 15.0
                            :blend-in-ms 200.0
                            :blend-out-ms 400.0
                            :inner-width 0.045
                            :outer-width 0.052
                            :glow-width 0.4
                            :start-color 0xD8F8D8
                            :end-color 0x6AF26A
                            :renderer-id "ray-composite"
                            :hook :barrage-ray-pre}}}))

    (edsl/register-entity!
      (edsl/create-entity-spec
        "entity_railgun_fx"
        {:entity-kind :scripted-ray
         :category :misc
         :width 0.2
         :height 0.2
         :client-tracking-range 96
         :update-interval 1
         :properties {:ray {:life-ticks 50
                            :length 15.0
                            :blend-in-ms 150.0
                            :blend-out-ms 800.0
                            :inner-width 0.09
                            :outer-width 0.13
                            :glow-width 1.1
                            :start-color 0xF1F0DE
                            :end-color 0xECAA5D
                            :renderer-id "ray-composite"
                            :hook :railgun-fx}}}))

    ;; Marker/UI family.
    (edsl/register-entity!
      (edsl/create-entity-spec
        "entity_tp_marking"
        {:entity-kind :scripted-marker
         :category :misc
         :width 0.5
         :height 0.5
         :client-tracking-range 64
         :update-interval 1
         :properties {:marker {:life-ticks 120
                               :follow-target? true
                               :ignore-depth? false
                               :available? true
                               :renderer-id "tp-marking"
                               :hook :tp-marking}}}))

    (edsl/register-entity!
      (edsl/create-entity-spec
        "entity_marker"
        {:entity-kind :scripted-marker
         :category :misc
         :width 0.5
         :height 0.5
         :client-tracking-range 64
         :update-interval 1
         :properties {:marker {:life-ticks 120
                               :follow-target? true
                               :ignore-depth? true
                               :available? true
                               :renderer-id "wire-marker"
                               :hook :marker}}}))

    ;; Shield / arc visuals (effect family).
    (edsl/register-entity!
      (edsl/create-entity-spec
        "entity_diamond_shield"
        {:entity-kind :scripted-effect
         :category :misc
         :width 1.8
         :height 1.8
         :client-tracking-range 64
         :update-interval 1
         :properties {:effect {:life-ticks 120
                               :follow-owner? true
                               :renderer-id "diamond-shield"
                               :hook :diamond-shield}}}))

    (edsl/register-entity!
      (edsl/create-entity-spec
        "entity_md_shield"
        {:entity-kind :scripted-effect
         :category :misc
         :width 1.8
         :height 1.8
         :client-tracking-range 64
         :update-interval 1
         :properties {:effect {:life-ticks 120
                               :follow-owner? true
                               :renderer-id "md-shield"
                               :hook :md-shield}}}))

    (edsl/register-entity!
      (edsl/create-entity-spec
        "entity_surround_arc"
        {:entity-kind :scripted-effect
         :category :misc
         :width 1.0
         :height 1.0
         :client-tracking-range 64
         :update-interval 1
         :properties {:effect {:life-ticks 100
                               :follow-owner? true
                               :renderer-id "surround-arc"
                               :hook :surround-arc}}}))

    (edsl/register-entity!
      (edsl/create-entity-spec
        "entity_ripple_mark"
        {:entity-kind :scripted-effect
         :category :misc
         :width 2.0
         :height 2.0
         :client-tracking-range 64
         :update-interval 1
         :properties {:effect {:life-ticks 20
                               :follow-owner? false
                               :renderer-id "ripple-mark"
                               :hook :ripple-mark}}}))

    (edsl/register-entity!
      (edsl/create-entity-spec
        "entity_blood_splash"
        {:entity-kind :scripted-effect
         :category :misc
         :width 1.0
         :height 1.0
         :client-tracking-range 64
         :update-interval 1
         :properties {:effect {:life-ticks 10
                               :follow-owner? false
                               :renderer-id "blood-splash"
                               :hook :blood-splash}}}))

    ;; Block-body family.
    (edsl/register-entity!
      (edsl/create-entity-spec
        "entity_block_body"
        {:entity-kind :scripted-block-body
         :category :misc
         :width 1.0
         :height 1.0
         :client-tracking-range 64
         :update-interval 1
         :properties {:block-body {:default-block-id "minecraft:stone"
                                   :gravity 0.05
                                   :damage 4.0
                                   :place-when-collide? true
                                   :renderer-id "block-body"
                                   :hook :entity-block}}}))

    (edsl/register-entity!
      (edsl/create-entity-spec
        "entity_magmanip_block_body"
        {:entity-kind :scripted-block-body
         :category :misc
         :width 1.0
         :height 1.0
         :client-tracking-range 64
         :update-interval 1
         :properties {:block-body {:default-block-id "minecraft:stone"
                                   :gravity 0.02
                                   :damage 6.0
                                   :place-when-collide? false
                                   :renderer-id "block-body"
                                   :hook :magmanip-block}}}))

    (edsl/register-entity!
      (edsl/create-entity-spec
        "entity_silbarn"
        {:entity-kind :scripted-block-body
         :category :misc
         :width 0.6
         :height 0.6
         :client-tracking-range 64
         :update-interval 1
         :properties {:block-body {:default-block-id "minecraft:iron_block"
                                   :gravity 0.05
                                   :damage 6.0
                                   :place-when-collide? false
                                   :renderer-id "block-body"
                                   :hook :silbarn}}}))))
