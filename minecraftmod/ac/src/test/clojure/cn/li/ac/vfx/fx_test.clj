(ns cn.li.ac.vfx.fx-test
  "S6 proof: every ac/vfx/fx/*.edn new-DSL VFX-effect conversion actually
   compiles (cn.li.node.surface/parse -> cn.li.node.compile via
   cn.li.vfx.scene/compile-doc!, against vfx-core's REAL scene vocabulary)
   and samples real ops against a representative age -- not just compiles.
   See each ac/vfx/fx/*.edn file's own docstring for why some effects
   compile to an intentionally empty :scene (the old component they wrapped
   never had a cn.li.vfx.final-engine/sample-node case either -- confirmed
   dead, not a regression)."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [cn.li.vfx.scene :as scene]))

(defn- read-fx [filename]
  (let [resource (io/resource (str "ac/vfx/fx/" filename))]
    (when-not resource
      (throw (ex-info "missing ac/vfx/fx resource" {:filename filename})))
    (read-string (slurp resource))))

(defn- input-types
  "The union of every {:type t} declared across a doc's :inputs :spawn/
   :update maps (deduped by field name) -- the real per-frame capability
   surface a scene sample reads, since the runtime supplies whichever
   fields are currently known (set at spawn, refreshed by :update) every
   single frame, not just the ones declared at one lifecycle point."
  [doc]
  (let [normalize (fn [v] (if (map? v) (:type v) v))]
    (into {} (map (fn [[k v]] [k (normalize v)]))
          (merge (get-in doc [:inputs :spawn]) (get-in doc [:inputs :update])))))

(defn- compile-and-sample!
  "doc, extra-capabilities (merged over :age/:progress/0.0 defaults) -> ops."
  ([doc capabilities] (compile-and-sample! doc capabilities 0.0))
  ([doc capabilities age]
   (let [ir (scene/compile-doc! (:scene doc) (input-types doc))
         program (scene/compile-program ir)
         input {:capabilities (merge {:age age :progress 0.0} capabilities)}]
     (scene/sample! program input))))

(deftest blood-retrograde-charge-compiles-to-an-empty-scene-test
  (let [doc (read-fx "blood_retrograde_charge.edn")]
    (testing "a bare EDN read gets a real self-contained DSL doc, not a broken/empty string"
      (is (= :blood-retrograde-charge (:id (scene/compile-doc! (:scene doc) (input-types doc))))))
    (testing ":vfx/charge-slow never had a real sampler case either -- zero ops is correct, not a gap"
      (is (empty? (compile-and-sample! doc {:speed 1.0}))))))

;; --- confirmed-dead-component effects: every one below wraps ONLY an old
;; component with no cn.li.vfx.final-engine/sample-node case (see
;; blood_retrograde_charge.edn's docstring for the grep-confirmed full
;; list) -- an empty :scene is the honest, exact-parity port. One shared
;; assert-empty-scene! helper instead of repeating the same three
;; assertions 17 times.

(defn- assert-empty-scene! [filename id]
  (let [doc (read-fx filename)]
    (is (= id (:id (scene/compile-doc! (:scene doc) (input-types doc)))) filename)
    (is (empty? (compile-and-sample! doc {})) filename)))

(deftest confirmed-dead-component-effects-compile-to-empty-scenes-test
  (doseq [[filename id] [["first_person_motion_session.edn" :first-person-motion-session]
                        ["block_progress_session.edn" :block-progress-session]
                        ["target_box_session.edn" :target-box-session]
                        ["directed_blastwave_charge.edn" :directed-blastwave-charge]
                        ["directed_blastwave_wave.edn" :directed-blastwave-wave]
                        ["vortex_column_session.edn" :vortex-column-session]
                        ["blood_retrograde_impact.edn" :blood-retrograde-impact]
                        ["arc_channel_session.edn" :arc-channel-session]
                        ["billboard_session.edn" :billboard-session]
                        ["block_scan_transient.edn" :block-scan-transient]
                        ["trajectory_ribbon_session.edn" :trajectory-ribbon-session]
                        ["beam_arc_fade.edn" :beam-arc-fade]
                        ["arc_strike_transient.edn" :arc-strike-transient]
                        ["target_mark_session.edn" :target-mark-session]
                        ["ray_fan_transient.edn" :ray-fan-transient]
                        ["particle_burst_trail_transient.edn" :particle-burst-trail-transient]
                        ["screen_flash_session.edn" :screen-flash-session]]]
    (assert-empty-scene! filename id)))

;; --- real/mixed effects: samples actual ops, not just compiles ---------

(deftest particle-burst-and-particle-session-emit-one-emitter-op-test
  (doseq [filename ["particle_burst.edn" "particle_session.edn"]]
    (let [doc (read-fx filename)
          ops (compile-and-sample! doc {:position {:x 1.0 :y 2.0 :z 3.0} :rate-per-tick 5
                                        :limit 10 :particle {:material :additive}})]
      (is (= 1 (count ops)) filename)
      (is (= :emitter (:kind (first ops))) filename)
      (is (= {:x 1.0 :y 2.0 :z 3.0} (:anchor (first ops))) filename)
      (is (= 10 (:limit (first ops)))) filename)))

(deftest audio-one-shot-emits-its-op-test
  (let [doc (read-fx "audio_one_shot.edn")
        ops (compile-and-sample! doc {:position {:x 0.0 :y 1.0 :z 0.0} :sound-id "a.b"
                                      :volume 0.8 :pitch 1.1})]
    (is (= 1 (count ops)))
    (is (= :audio-one-shot (:kind (first ops))))
    (is (= "a.b" (:sound-id (first ops))))))

(deftest audio-loop-session-carries-fixed-instance-key-test
  (let [doc (read-fx "audio_loop_session.edn")
        ops (compile-and-sample! doc {:position {:x 0.0 :y 1.0 :z 0.0} :sound-id "loop"
                                      :volume 1.0 :pitch 1.0})]
    (is (= 1 (count ops)))
    (is (= :audio-loop (:kind (first ops))))
    (is (= [:effect-instance :audio-loop] (:instance-key (first ops))))
    (is (true? (:stop-on-destroy? (first ops))))))

(deftest camera-fov-session-drops-the-dead-operation-literal-test
  (let [doc (read-fx "camera_fov_session.edn")
        ops (compile-and-sample! doc {:offset 2.5})]
    (is (= 1 (count ops)))
    (is (= :camera-fov (:kind (first ops))))
    (is (= 2.5 (:value (first ops))))
    (is (= 1 (:duration-ticks (first ops))))))

(deftest beam-session-and-ray-beam-transient-drop-the-dead-life-ticks-field-test
  (doseq [filename ["beam_session.edn" "ray_beam_transient.edn"]]
    (let [doc (read-fx filename)
          ops (compile-and-sample! doc {:start {:x 0.0 :y 0.0 :z 0.0} :end {:x 1.0 :y 0.0 :z 0.0}
                                        :life-ticks 40 :grow-ticks 4 :style :thin
                                        :bounds-radius 1.0})]
      (is (= 1 (count ops)) filename)
      (is (= :ray-beam (:kind (first ops))) filename)
      (is (= 4 (:grow-ticks (first ops))) filename)
      (is (not (contains? (first ops) :life-ticks)) filename))))

(deftest ring-particle-field-lerps-radius-by-progress-test
  (let [doc (read-fx "ring_particle_field.edn")
        ops (compile-and-sample! doc {:position {:x 0.0 :y 0.0 :z 0.0} :ring-from 1.0 :ring-to 3.0
                                      :segments 16 :ring-color [255 0 0 255] :rate-per-tick 2
                                      :limit 8 :particle {:material :additive} :progress 0.5})]
    (is (= 2 (count ops)))
    (is (= :ring (:kind (first ops))))
    (is (= 2.0 (:radius (first ops))) "progress=0.5 -> lerp(1.0,3.0,0.5)=2.0")
    (is (= :emitter (:kind (second ops))))))

(deftest endpoint-burst-emits-both-emitters-and-audio-test
  (let [doc (read-fx "endpoint_burst.edn")
        ops (compile-and-sample! doc {:from {:x 0.0 :y 0.0 :z 0.0} :to {:x 5.0 :y 0.0 :z 0.0}
                                      :duration-ticks 20 :rate-per-tick 2 :limit 8
                                      :particle {:material :additive} :sound-id "tp"
                                      :sound-volume 1.0 :sound-pitch 1.0})]
    (is (= 3 (count ops)))
    (is (= [:emitter :emitter :audio-one-shot] (mapv :kind ops)))
    (is (= {:x 0.0 :y 0.0 :z 0.0} (:anchor (first ops))))
    (is (= {:x 5.0 :y 0.0 :z 0.0} (:anchor (second ops))))
    (is (= {:x 5.0 :y 0.0 :z 0.0} (:position (nth ops 2))) "audio plays at :to, matching the old node")))

(defn- assert-fade-beam! [filename before-caps after-caps]
  (let [doc (read-fx filename)]
    (testing (str filename " before both gates -- only the unconditional audio fires")
      (is (= [:audio-one-shot] (mapv :kind (compile-and-sample! doc before-caps 0.0)))))
    (testing (str filename " after both gates -- beam, audio, faded beam")
      (let [ops (compile-and-sample! doc after-caps 100.0)]
        (is (= [:beam :audio-one-shot :beam] (mapv :kind ops)))
        (is (= 0.5 (:alpha (nth ops 2)))
            "age 100, fade-from-tick 50 fade-to-tick 150 -> p=0.5, lerp(1.0,0.0,0.5)=0.5")))))

(deftest beam-fade-audio-gates-on-dynamic-at-and-computes-fade-alpha-test
  (assert-fade-beam!
   "beam_fade_audio.edn"
   {:start {:x 0.0 :y 0.0 :z 0.0} :end {:x 1.0 :y 0.0 :z 0.0} :duration-ticks 200
    :beam-at 200 :grow-ticks 4 :layers :a :sound-id "s" :sound-volume 1.0 :sound-pitch 1.0
    :sound-position {:x 0.0 :y 0.0 :z 0.0} :fade-at 200 :fade-from-tick 50 :fade-to-tick 150
    :fade-from-alpha 1.0 :fade-to-alpha 0.0 :fade-layers :b}
   {:start {:x 0.0 :y 0.0 :z 0.0} :end {:x 1.0 :y 0.0 :z 0.0} :duration-ticks 200
    :beam-at 0 :grow-ticks 4 :layers :a :sound-id "s" :sound-volume 1.0 :sound-pitch 1.0
    :sound-position {:x 0.0 :y 0.0 :z 0.0} :fade-at 0 :fade-from-tick 50 :fade-to-tick 150
    :fade-from-alpha 1.0 :fade-to-alpha 0.0 :fade-layers :b}))

(defn- assert-fade-ring! [filename center-key before-caps after-caps]
  (let [doc (read-fx filename)]
    (testing (str filename " before the fade gate -- only ring + audio")
      (is (= [:ring :audio-one-shot] (mapv :kind (compile-and-sample! doc before-caps 0.0)))))
    (testing (str filename " after the fade gate -- ring, audio, faded ring")
      (let [ops (compile-and-sample! doc after-caps 100.0)]
        (is (= [:ring :audio-one-shot :ring] (mapv :kind ops)))
        (is (= 0.5 (:alpha (nth ops 2)))
            "age 100, fade-at 50 fade-to-tick 150 -> p=0.5, lerp(1.0,0.0,0.5)=0.5")
        (is (contains? (nth ops 2) center-key))))))

(deftest ring-fade-audio-gates-on-dynamic-at-and-computes-fade-alpha-test
  (assert-fade-ring!
   "ring_fade_audio.edn" :center
   {:position {:x 0.0 :y 0.0 :z 0.0} :duration-ticks 200 :ring-from 1.0 :ring-to 2.0
    :ring-segments 16 :ring-color :c :fade-at 200 :fade-to-tick 150 :fade-ring-from 1.0
    :fade-ring-to 2.0 :fade-segments 16 :fade-color :c :sound-id "s" :sound-volume 1.0
    :sound-pitch 1.0}
   {:position {:x 0.0 :y 0.0 :z 0.0} :duration-ticks 200 :ring-from 1.0 :ring-to 2.0
    :ring-segments 16 :ring-color :c :fade-at 50 :fade-to-tick 150 :fade-ring-from 1.0
    :fade-ring-to 2.0 :fade-segments 16 :fade-color :c :sound-id "s" :sound-volume 1.0
    :sound-pitch 1.0}))

(deftest arc-ring-session-drops-the-dead-arc-field-and-computes-fade-alpha-test
  (assert-fade-ring!
   "arc_ring_session.edn" :center
   {:start {:x 0.0 :y 0.0 :z 0.0} :end {:x 1.0 :y 0.0 :z 0.0} :duration-ticks 200
    :arc-spacing :a :arc-radius 1.0 :arc-count-limit 4 :arc-life-ticks 8 :ring-from 1.0
    :ring-to 2.0 :ring-segments 16 :ring-color :c :sound-id "s" :sound-volume 1.0
    :sound-pitch 1.0 :sound-position {:x 0.0 :y 0.0 :z 0.0} :fade-at 200 :fade-to-tick 150
    :fade-ring-from 1.0 :fade-ring-to 2.0 :fade-segments 16 :fade-color :c}
   {:start {:x 0.0 :y 0.0 :z 0.0} :end {:x 1.0 :y 0.0 :z 0.0} :duration-ticks 200
    :arc-spacing :a :arc-radius 1.0 :arc-count-limit 4 :arc-life-ticks 8 :ring-from 1.0
    :ring-to 2.0 :ring-segments 16 :ring-color :c :sound-id "s" :sound-volume 1.0
    :sound-pitch 1.0 :sound-position {:x 0.0 :y 0.0 :z 0.0} :fade-at 50 :fade-to-tick 150
    :fade-ring-from 1.0 :fade-ring-to 2.0 :fade-segments 16 :fade-color :c}))

(deftest arc-ring-fade-audio-drops-the-dead-arc-field-and-computes-fade-alpha-test
  (assert-fade-ring!
   "arc_ring_fade_audio.edn" :center
   {:start {:x 0.0 :y 0.0 :z 0.0} :end {:x 1.0 :y 0.0 :z 0.0} :ring-center {:x 0.0 :y 0.0 :z 0.0}
    :duration-ticks 200 :ring-from 1.0 :ring-to 2.0 :ring-segments 16 :ring-color :c
    :arc-spacing :a :arc-radius 1.0 :arc-count-limit 4 :arc-life-ticks 8 :fade-at 200
    :fade-to-tick 150 :fade-ring-from 1.0 :fade-ring-to 2.0 :fade-segments 16 :fade-color :c
    :sound-id "s" :sound-volume 1.0 :sound-pitch 1.0 :sound-position {:x 0.0 :y 0.0 :z 0.0}}
   {:start {:x 0.0 :y 0.0 :z 0.0} :end {:x 1.0 :y 0.0 :z 0.0} :ring-center {:x 0.0 :y 0.0 :z 0.0}
    :duration-ticks 200 :ring-from 1.0 :ring-to 2.0 :ring-segments 16 :ring-color :c
    :arc-spacing :a :arc-radius 1.0 :arc-count-limit 4 :arc-life-ticks 8 :fade-at 50
    :fade-to-tick 150 :fade-ring-from 1.0 :fade-ring-to 2.0 :fade-segments 16 :fade-color :c
    :sound-id "s" :sound-volume 1.0 :sound-pitch 1.0 :sound-position {:x 0.0 :y 0.0 :z 0.0}}))

(deftest energy-orb-session-emits-three-rings-emitter-and-a-fading-outer-ring-test
  (let [doc (read-fx "energy_orb_session.edn")
        caps {:position {:x 0.0 :y 0.0 :z 0.0} :radius 4.0 :segments 16 :outer-color :o
             :inner-color :i :core-color :c :rate-per-tick 2 :limit 8
             :particle {:material :additive}}]
    (testing "age 0 -- fade ring at full alpha (fade-p=0)"
      (let [ops (compile-and-sample! doc caps 0.0)]
        (is (= [:ring :ring :ring :emitter :ring] (mapv :kind ops)))
        (is (= 1.0 (:alpha (nth ops 4))))
        (is (= 4.0 (:radius (nth ops 4))) "fade ring radius lerp(radius,0.0,progress=0)=radius")))
    (testing "age 4096 -- fade ring fully transparent (fade-p=1)"
      (let [ops (compile-and-sample! doc caps 4096.0)]
        (is (= 0.0 (:alpha (nth ops 4))))))))

(deftest teleport-marker-drops-the-dead-humanoid-marker-and-emits-the-particle-burst-test
  (let [doc (read-fx "teleport_marker.edn")
        ops (compile-and-sample! doc {:owner "player-1" :position {:x 0.0 :y 0.0 :z 0.0}
                                      :color [255 255 255 255] :particle-chance 0.5})]
    (is (= 1 (count ops)))
    (is (= :emitter (:kind (first ops))))
    (is (= 0.5 (:chance (first ops))))
    (is (= :additive (:material (:particle (first ops)))))))

(deftest terrain-shockwave-transient-drops-both-dead-waves-and-plays-audio-test
  (let [doc (read-fx "terrain_shockwave_transient.edn")
        ops (compile-and-sample! doc {:origin {:x 0.0 :y 0.0 :z 0.0} :direction {:x 1.0 :y 0.0 :z 0.0}
                                      :surface-hits [] :seed 1 :sound-id "boom"
                                      :sound-volume 1.0 :sound-pitch 1.0})]
    (is (= 1 (count ops)))
    (is (= :audio-one-shot (:kind (first ops))))
    (is (= {:x 0.0 :y 0.0 :z 0.0} (:position (first ops))))))

(deftest teleport-trail-and-particle-trail-audio-drop-the-dead-particle-trail-test
  (let [t1-ops (compile-and-sample!
                (read-fx "teleport_trail_transient.edn")
                {:start {:x 0.0 :y 0.0 :z 0.0} :end {:x 1.0 :y 0.0 :z 0.0} :sound-id "s"
                 :sound-volume 1.0 :sound-pitch 1.0})
        t2-ops (compile-and-sample!
                (read-fx "particle_trail_audio_transient.edn")
                {:start {:x 0.0 :y 0.0 :z 0.0} :end {:x 1.0 :y 0.0 :z 0.0} :spacing :a
                 :radius :a :count-limit 1 :life-ticks 1 :texture "t" :velocity :a :size :a
                 :alpha :a :fade-in 1 :fade-out 1 :sound-id "s" :sound-position {:x 1.0 :y 0.0 :z 0.0}
                 :sound-volume 1.0 :sound-pitch 1.0})]
    (is (= [:audio-one-shot] (mapv :kind t1-ops)))
    (is (= {:x 1.0 :y 0.0 :z 0.0} (:position (first t1-ops))) "teleport_trail_transient plays audio at :end")
    (is (= [:audio-one-shot] (mapv :kind t2-ops)))
    (is (= {:x 1.0 :y 0.0 :z 0.0} (:position (first t2-ops)))
        "particle_trail_audio_transient plays audio at :sound-position")))
