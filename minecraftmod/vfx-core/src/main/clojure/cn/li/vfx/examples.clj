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
   :vfx/beam-bounds {:component :vfx/beam-bounds :start point-example
                     :end point-example :radius 0.1}
   :vfx/arc-field {:component :vfx/arc-field :start point-example :end point-example
                   :spacing {:min 1.0 :max 1.0} :radius {:min 0.1 :max 0.1}
                   :count-limit 1 :life-ticks 1 :seed {:ref [:state :seed]}}
   :vfx/vortex-column {:component :vfx/vortex-column :base point-example
                       :axis {:vec3 [0.0 1.0 0.0]} :height 1.0
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
                    :volume 1.0 :pitch 1.0
                    :instance-key [:effect-instance :generic] :stop-on-destroy? true}})
