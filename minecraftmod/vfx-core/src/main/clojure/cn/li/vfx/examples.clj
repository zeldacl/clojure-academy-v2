(ns cn.li.vfx.examples
  "Generic examples for every VFX middle-layer component.")

(def ^:private point-example {:vec3 [0.0 0.0 0.0]})
(def ^:private ring-example {:component :vfx/ring :center point-example
                             :radius 0.1 :segments 3})

(def vfx-node-examples
  {:vfx/timeline {:component :vfx/timeline :duration-ticks 1
                  :children [{:at 0 :node ring-example}]}
   :vfx/event-switch {:component :vfx/event-switch
                      :cases {:default ring-example}}
   :vfx/beam {:component :vfx/beam :start point-example :end point-example
              :grow-ticks 1 :layers [{:shape :line :width 0.01 :color [255 255 255 255]}]}
   :vfx/ray-beam {:component :vfx/ray-beam :start point-example :end point-example
                  :life-ticks 30 :grow-ticks 4 :style {}}
   :vfx/ray-fan {:component :vfx/ray-fan :origin point-example
                 :direction point-example :length 15.0 :count 25
                 :yaw-range-degrees 55.0 :pitch-range-degrees 30.0
                 :life-ticks 50 :grow-ticks 4 :style {}
                 :seed {:ref [:state :seed]}}
   :vfx/arc-strike {:component :vfx/arc-strike
                    :start point-example :end point-example
                    :aoe-origin point-example :aoe-points []
                    :arc-life-ticks 20 :pattern :strong
                    :hand-origin? true :sound-id "generic"
                    :sound-volume 0.6 :sound-pitch 1.0
                    :sound-position point-example :bounds-radius 2.0
                    :seed {:ref [:state :seed]}}
   :vfx/channel-arc {:component :vfx/channel-arc :mode :block
                     :caster point-example :target point-example
                     :block-pos [0 64 0] :block-bounds nil
                     :good? true :charge-ticks 10 :visual-max-ticks 40
                     :style {} :seed {:ref [:state :seed]}}
   :vfx/block-scan {:component :vfx/block-scan :origin point-example
                    :range 16.0 :filter {:tags ["example:blocks"]}
                    :advanced? false :life-ticks 20
                    :rescan-interval 5 :max-results 256
                    :texture "generic/highlight.png"
                    :base-color [255 255 255 160]
                    :tier-colors {}
                    :seed {:ref [:state :seed]}}
   :vfx/block-progress {:component :vfx/block-progress
                        :target [0.0 64.0 0.0]
                        :progress 0.5
                        :color [106 242 106 180]
                        :pulse-period 0.3}
   :vfx/beam-bounds {:component :vfx/beam-bounds :start point-example
                     :end point-example :radius 0.1}
   :vfx/arc-field {:component :vfx/arc-field :start point-example :end point-example
                   :spacing {:min 1.0 :max 1.0} :radius {:min 0.1 :max 0.1}
                   :count-limit 1 :life-ticks 1 :seed {:ref [:state :seed]}}
   :vfx/vortex-column {:component :vfx/vortex-column :base point-example
                       :axis {:vec3 [0.0 1.0 0.0]} :alpha 1.0 :orientation nil :height 1.0
                       :spacing {:min 1.0 :max 1.0}
                       :radius {:min 0.1 :max 0.1}
                       :count-limit 1 :life-ticks 1
                       :seed {:ref [:state :seed]}}
   :vfx/ring ring-example
   :vfx/billboard-sequence {:component :vfx/billboard-sequence
                            :anchor point-example :texture-pattern "generic/%d.png"
                            :frame-count 1 :frame-duration-ms 40 :half-size 0.1}
   :vfx/model-marker {:component :vfx/model-marker
                      :anchor point-example
                      :owner {:ref [:input :owner]}
                      :texture-pattern "generic/%d.png"
                      :frame-count 1
                      :frame-period-ticks 1.0
                      :parts [{:hw 0.25 :hh 0.25 :hd 0.25
                               :cx 0.0 :cy 0.25
                               :front [0.0 0.25 0.0 0.25]
                               :back [0.25 0.5 0.0 0.25]
                               :right [0.5 0.75 0.0 0.25]
                               :left [0.75 1.0 0.0 0.25]
                               :top [0.0 0.25 0.25 0.5]
                               :bottom [0.25 0.5 0.25 0.5]}]
                      :color [255 255 255 255]
                      :facing :camera
                      :no-depth-test? false
                      :no-cull? false}
   :vfx/emitter {:component :vfx/emitter :anchor point-example :rate-per-tick 1
                 :limit 1 :particle {:material :additive :life-ticks 1 :speed 0.01}}
   :vfx/ribbon {:component :vfx/ribbon :points {:ref [:state :trail-points]}
                :width 0.01 :max-points 1 :color [255 255 255 255]}
   :vfx/charge-slow {:component :vfx/charge-slow :speed 0.1}
   :vfx/charge-ring {:component :vfx/charge-ring :center point-example
                     :charge-ticks 1 :max-charge-ticks 50 :points 16
                     :base-radius 0.1 :radius-growth 0.16
                     :pulse-amplitude 0.025 :pulse-frequency 0.22
                     :outer-color [236 170 93 170] :core-color [241 240 222 220]
                     :punched? false}
   :vfx/directional-wave {:component :vfx/directional-wave :position point-example
                          :direction {:vec3 [0.0 0.0 1.0]} :ring-count-min 2
                          :ring-count-max 3 :life-ticks 15
                          :ring-life-min 8 :ring-life-max 12
                          :ring-life-jitter 0.0 :ring-offset-step 1.5
                          :ring-offset-jitter 0.3 :ring-size-min 0.8
                          :ring-size-max 1.2 :time-offset-step 2.0
                          :time-offset-jitter 1.0 :fade-in-ratio 0.2
                          :full-ratio 0.8 :fade-out-ratio 0.2
                          :growth-ticks 20.0 :initial-scale 0.4
                          :mid-scale 0.8 :mid-ratio 0.2 :final-scale 1.5
                          :forward-speed 0.025 :texture "generic/glow_circle.png"
                          :color [188 252 238 200] :seed 0}
   :vfx/impact-burst {:component :vfx/impact-burst
                      :origin point-example :look-dir {:vec3 [0.0 0.0 1.0]}
                      :target-width 0.6 :target-height 1.8
                      :surface-hits [] :splash-count 8
                      :splash-life-ticks 10
                      :splash-texture-pattern "academy:textures/effects/blood_splash/%d.png"
                      :splash-frame-count 10 :splash-frame-duration-ms 50
                      :splash-size 1.4 :spray-textures []
                      :spray-life-ticks 24 :spray-duplicates 2 :seed 0}
   :vfx/trajectory-ribbon {:component :vfx/trajectory-ribbon
                           :origin point-example
                           :initial-velocity {:vec3 [0.0 1.0 0.0]}
                           :look-dir {:vec3 [0.0 0.0 1.0]}
                           :lateral-offset 0.08
                           :forward-offset 0.12
                           :vertical-offset -0.06
                           :drag 0.98 :gravity 1.9 :dt 0.02
                           :segments 32 :width 0.02 :can-perform? true
                           :style {:ready-color [255 255 255 255]
                                   :blocked-color [255 51 51 255]
                                   :height 0.02}}
   :vfx/fade {:component :vfx/fade :from-tick 0 :to-tick 1
              :from-alpha 1.0 :to-alpha 0.0 :child ring-example}
   :vfx/scale {:component :vfx/scale :from 0.0 :to 1.0
               :from-tick 0 :to-tick 1 :child ring-example}
   :vfx/noise {:component :vfx/noise :seed {:ref [:state :seed]}
               :amplitude 0.1 :frequency 1.0 :target :width :child ring-example}
   :vfx/attach {:component :vfx/attach :anchor-type :owner
                :owner {:ref [:input :source-owner]} :offset point-example
                :child ring-example}
   :vfx/first-person-transform {:component :vfx/first-person-transform
                                :offset {:right 0.0 :up 0.0 :forward 0.0}
                                :child ring-example}
   :vfx/camera {:component :vfx/camera :operation :pitch-impulse :value 0.0 :duration-ticks 1}
   :vfx/audio-one-shot {:component :vfx/audio-one-shot :sound-id "generic" :position point-example}
   :vfx/audio-loop {:component :vfx/audio-loop :sound-id "generic"
                    :position point-example
                    :volume 1.0 :pitch 1.0
                    :instance-key [:effect-instance :generic] :stop-on-destroy? true}})
