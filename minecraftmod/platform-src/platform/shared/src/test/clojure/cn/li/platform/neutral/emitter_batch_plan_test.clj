(ns cn.li.platform.neutral.emitter-batch-plan-test
  "A Niagara emitter's particles reach the renderer as quads.

   They did not, and the failure was silent at three points at once. The
   plan's :particle branch guarded on `(instance? ParticleBuffer ...)`, and
   ParticleBuffer has NO producer anywhere in the repo -- the emitter stack
   fills a ParticleColumns, its successor. The batch payload also carried
   neither :operation nor :primitive, so it never reached that branch in the
   first place, and the buffer sat under :particles while the branch read
   :particle-buffer. Every emitter in the game drew nothing.

   None of that could be caught by asserting shapes: an emitter batch is a
   well-formed map whether or not anything downstream consumes it, and an
   empty op list is a well-formed plan. So these assert quad COUNT and quad
   POSITION against the columns that were actually filled.

   The layout map here is written out rather than built, because
   platform-shared must not depend on vfx-core. cn.li.vfx.layout is the
   producer, and emitter-layout-matches-the-render-plan-test over in
   vfx-core pins build's output to exactly this map -- that pair is what
   keeps the two ends from drifting apart again."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.platform.neutral.vfx-render-plan :as plan])
  (:import [cn.li.mcmod.runtime.vfx ParticleColumns]))

(def ^:private capacity 4)

(def ^:private layout
  "cn.li.vfx.layout/build over
     {:age :float :alpha :float :color :color :lifetime :float
      :position :vec3 :size :float}
   which assigns columns by sorted attribute name, floats and ints in
   separate banks."
  {:capacity capacity
   :float-cols 7
   :int-cols 1
   :cols {:age [0] :alpha [1] :color [0] :lifetime [2]
          :position [3 4 5] :size [6]}})

(defn- rgba->packed
  "The single int cn.li.vfx.layout packs a :color attribute into."
  [[r g b a]]
  (bit-or (bit-shift-left (long a) 24) (bit-shift-left (long r) 16)
          (bit-shift-left (long g) 8) (long b)))

(defn- columns
  "A ParticleColumns holding `particles`, each
   {:position [x y z] :size s :alpha a :age t :lifetime l :color [r g b a]}."
  ^ParticleColumns [particles]
  (let [pc (ParticleColumns. capacity 7 1)
        ^floats fs (.floats pc)
        ^ints is (.ints pc)
        put (fn [col i v] (aset fs (+ (* (long col) capacity) (long i)) (float v)))]
    (.reserve pc (count particles))
    (doseq [[i p] (map-indexed vector particles)
            :let [[x y z] (:position p)]]
      (put 3 i x) (put 4 i y) (put 5 i z)
      (put 6 i (:size p)) (put 1 i (:alpha p))
      (put 0 i (:age p)) (put 2 i (:lifetime p))
      ;; unchecked-int, not int: a full alpha sets bit 31, so the packed
      ;; value exceeds Integer.MAX_VALUE as a long and a checked cast
      ;; throws. The renderer masks the channels back out either way.
      (aset is (+ (* 0 capacity) (long i)) (unchecked-int (rgba->packed (:color p)))))
    pc))

(defn- emitter-plan
  "One emitter batch payload through the neutral plan, exactly as
   cn.li.vfx.frame/->java-frame now builds it."
  [particles spec]
  (plan/neutral-op->plan
   {:operation :draw-batch
    :stage :world-translucent
    :primitive :particle
    :instance-key :test
    :layout layout
    :material {:particle spec}
    :particles (columns particles)}
   nil))

(defn- quads [p] (filterv #(= :quad (:kind %)) (:ops p)))

(def ^:private green [0 255 0 255])

(def ^:private two-particles
  [{:position [1.0 2.0 3.0] :size 0.5 :alpha 200 :age 0.0 :lifetime 20.0 :color green}
   {:position [-1.0 0.0 5.0] :size 0.25 :alpha 100 :age 10.0 :lifetime 20.0 :color green}])

(deftest emitter-particles-become-quads-test
  (testing "one quad per live particle, not per capacity slot"
    (is (= 2 (count (quads (emitter-plan two-particles {:texture "t.png"}))))))
  (testing "an emitter that has spawned nothing draws nothing"
    (is (= 0 (count (quads (emitter-plan [] {:texture "t.png"})))))))

(deftest quad-sits-at-the-particle-position-test
  (let [[q0 q1] (quads (emitter-plan two-particles {:texture "t.png"}))
        center (fn [q] [(/ (+ (.-x ^cn.li.mcmod.math.V3 (:p0 q))
                              (.-x ^cn.li.mcmod.math.V3 (:p2 q))) 2.0)
                        (.-y ^cn.li.mcmod.math.V3 (:p0 q))
                        (/ (+ (.-z ^cn.li.mcmod.math.V3 (:p0 q))
                              (.-z ^cn.li.mcmod.math.V3 (:p2 q))) 2.0)])]
    (testing "each quad is centred on its own particle's :position column"
      (is (= [1.0 2.0 3.0] (center q0)))
      (is (= [-1.0 0.0 5.0] (center q1))))
    (testing "and sized by its own :size column, not one shared size"
      (is (= 1.0 (- (.-x ^cn.li.mcmod.math.V3 (:p2 q0))
                    (.-x ^cn.li.mcmod.math.V3 (:p0 q0)))))
      (is (= 0.5 (- (.-x ^cn.li.mcmod.math.V3 (:p2 q1))
                    (.-x ^cn.li.mcmod.math.V3 (:p0 q1))))))))

(deftest particle-columns-drive-colour-and-alpha-test
  (let [[q0 q1] (quads (emitter-plan two-particles {:texture "t.png"}))]
    (testing "rgb is unpacked from the particle's own :color column"
      (is (= [0 255 0] (vec (take 3 (:color q0))))))
    (testing "alpha comes from the :alpha column, so two particles of one
              emitter can differ"
      (is (= 200 (nth (:color q0) 3)))
      (is (= 100 (nth (:color q1) 3))))))

(deftest fade-envelope-applies-per-particle-age-test
  ;; The envelope is emitter-wide (it lives on the material spec) but the
  ;; age and lifetime it reads are per-particle columns, so two particles
  ;; spawned at different times must be at different points of the ramp.
  (let [spec {:texture "t.png" :fade-in-ticks 5 :fade-out-ticks 10}
        at (fn [age] (nth (:color (first (quads (emitter-plan
                                                 [{:position [0.0 0.0 0.0] :size 0.5
                                                   :alpha 200 :age age :lifetime 20.0
                                                   :color green}]
                                                 spec))))
                          3))]
    (is (= 0 (at 0.0)))
    (is (= 100 (at 2.5)))
    (is (= 200 (at 5.0)))
    (is (= 200 (at 10.0)))
    (is (= 100 (at 15.0)))
    (is (= 0 (at 20.0)))))

(deftest a-dead-stripped-column-falls-back-rather-than-crashing-test
  ;; cn.li.vfx.layout dead-strips any attribute no module writes, so an
  ;; emitter with a constant size genuinely has no :size column. That must
  ;; take the material's size, not NPE on a nil column index.
  (let [bare {:capacity capacity :float-cols 3 :int-cols 0
              :cols {:position [0 1 2]}}
        pc (let [pc (ParticleColumns. capacity 3 0)
                 ^floats fs (.floats pc)]
             (.reserve pc 1)
             (aset fs 0 (float 1.0)) (aset fs capacity (float 2.0))
             (aset fs (* 2 capacity) (float 3.0))
             pc)
        p (plan/neutral-op->plan
           {:operation :draw-batch :stage :world-translucent :primitive :particle
            :layout bare :material {:particle {:texture "t.png" :size 0.5
                                               :color green}}
            :particles pc}
           nil)
        q (first (quads p))]
    (is (some? q) "a layout with only :position must still draw")
    (is (= 1.0 (- (.-x ^cn.li.mcmod.math.V3 (:p2 q))
                  (.-x ^cn.li.mcmod.math.V3 (:p0 q))))
        "the material's :size is used when the column was stripped")
    (is (= green (:color q))
        "and the material's colour when the :color column was stripped"))
  (testing "a layout with no :position column at all draws nothing"
    (is (= [] (quads (plan/neutral-op->plan
                      {:operation :draw-batch :stage :world-translucent
                       :primitive :particle
                       :layout {:capacity capacity :float-cols 1 :int-cols 0
                                :cols {:age [0]}}
                       :material {:particle {:texture "t.png"}}
                       :particles (ParticleColumns. capacity 1 0)}
                      nil))))))
