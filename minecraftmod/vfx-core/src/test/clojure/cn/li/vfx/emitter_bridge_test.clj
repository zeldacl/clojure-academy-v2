(ns cn.li.vfx.emitter-bridge-test
  "The producing end of the emitter -> renderer bridge.

   cn.li.platform.neutral.emitter-batch-plan-test is the consuming end. It
   cannot depend on vfx-core, so it writes cn.li.vfx.layout/build's output
   by hand; these tests pin build to exactly that map, and pin
   ->java-frame's emitter payload to the op shape that plan dispatches on.

   Both ends passing in isolation is what let the bridge stay broken: the
   emitter stack filled a ParticleColumns correctly and the render plan
   expanded particles correctly, but the payload between them carried no
   :primitive, used a different key for the buffer, and was guarded on a
   class with no producer. Nothing rendered and no test noticed."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.vfx.frame :as frame]
            [cn.li.vfx.layout :as layout]
            [cn.li.vfx.runtime :as runtime])
  (:import [cn.li.mcmod.runtime.vfx ParticleColumns]))

(def ^:private full-attrs
  {:age :float :alpha :float :color :color :lifetime :float
   :position :vec3 :size :float})

(deftest layout-matches-the-render-plans-hand-written-copy-test
  ;; Keep this literal identical to `layout` in
  ;; cn.li.platform.neutral.emitter-batch-plan-test. If build's column
  ;; assignment changes, this fails here rather than silently mis-decoding
  ;; particles over there.
  (is (= {:capacity 4
          :float-cols 7
          :int-cols 1
          :cols {:age [0] :alpha [1] :color [0] :lifetime [2]
                 :position [3 4 5] :size [6]}}
         (layout/build full-attrs 4))))

(deftest dead-stripped-attributes-leave-no-column-test
  ;; The render plan's fallback path depends on this: an absent column must
  ;; be absent from :cols, not present-but-unwritten.
  (let [l (layout/build {:position :vec3} 4)]
    (is (nil? (layout/column l :size)))
    (is (nil? (layout/column l :color)))
    (is (= [0 1 2] (layout/column l :position)))))

(defn- emitter-frame
  "->java-frame over one instance carrying a single emitter."
  [material]
  (frame/->java-frame
   1 0
   {[:k] {:scene []
          :emitters [{:layout (layout/build full-attrs 4)
                      :buffer (doto (ParticleColumns. 4 7 1) (.reserve 2))
                      :material material}]}}))

(deftest emitter-batch-payload-is-a-dispatchable-op-test
  (let [batch (first (.batches (emitter-frame {:particle {:texture "t.png"}})))
        payload (.payload batch)]
    (testing "the payload IS the op -- the loader hands it straight to
              neutral-op->plan, which dispatches on these two keys"
      (is (= :draw-batch (:operation payload)))
      (is (= :particle (:primitive payload))))
    (testing "the buffer is under the key the plan reads"
      (is (instance? ParticleColumns (:particles payload))))
    (testing "and the layout travels with it, since the plan needs the
              column indices to decode the buffer at all"
      (is (= [3 4 5] (layout/column (:layout payload) :position))))
    (testing "the emitter's material rides on the batch rather than in a
              per-particle column"
      (is (= "t.png" (get-in payload [:material :particle :texture]))))
    (testing "the Java-level batch fields still describe it for the sorter"
      (is (= "particle" (.primitive batch)))
      (is (= 2 (.instanceCount batch))))))

(deftest an-emitter-with-no-material-still-produces-a-batch-test
  ;; :material is optional on an emitter decl, so the payload carries nil.
  ;; The plan defaults it to {} -- this pins that nil is what it gets,
  ;; rather than the key being absent and some other default applying.
  (let [payload (.payload (first (.batches (emitter-frame nil))))]
    (is (contains? payload :material))
    (is (nil? (:material payload)))))

;; --- continuous emission ----------------------------------------------------
;;
;; compile-instance runs :spawn once for :burst; everything after that is
;; :rate. Before it existed an emitter could only ever hold the particles it
;; was created with, which is why a session effect like the teleport marker
;; -- whose whole visual IS a steady trickle -- had nothing to migrate to.

(def ^:private trickle-decl
  "8 particles/second = 0.4/tick, the rate main's EntityTPMarking emits at
   (one particle on a 40% roll, every tick)."
  {:id :trickle :capacity 64 :seed 7 :rate 8.0
   :attrs {:position :vec3 :velocity :vec3 :age :float :lifetime :float}
   :spawn [{:module :spawn/burst :count 0}
           {:module :spawn/set :attr :position :value [0.0 0.0 0.0]}
           {:module :spawn/set :attr :velocity :value [0.0 1.0 0.0]}
           {:module :spawn/set :attr :age :value 0.0}
           {:module :spawn/set :attr :lifetime :value 100.0}]
   :update [{:module :integrate}]})

(defn- ticked
  "A store with one `decl` instance, advanced `n` ticks of 0.05s."
  [decl n]
  (let [store (runtime/create-store {:e {:emitters [decl]}})]
    (runtime/ensure! store [:k] {:effect-id :e :seed 1 :user {}})
    (dotimes [_ n] (runtime/tick! store 0.05))
    (-> (runtime/lookup store [:k]) :emitters first :buffer)))

(deftest a-sub-one-per-tick-rate-still-emits-test
  (testing "0.4 particles per tick accumulates rather than truncating to 0"
    ;; The whole point of the fractional accumulator: (long 0.4) is 0, so
    ;; dropping the remainder each tick would emit nothing, forever.
    (is (= 0 (.size (ticked trickle-decl 1))))
    (is (= 1 (.size (ticked trickle-decl 3))))
    (is (= 4 (.size (ticked trickle-decl 10))))
    (is (= 40 (.size (ticked trickle-decl 100))))))

(deftest rate-zero-emits-nothing-after-the-burst-test
  (let [burst-only (assoc trickle-decl :rate 0.0
                          :spawn (assoc-in (:spawn trickle-decl) [0 :count] 3))]
    (is (= 3 (.size (ticked burst-only 1))))
    (is (= 3 (.size (ticked burst-only 50)))
        "no :rate means the burst is all an emitter ever produces")))

(deftest emission-stops-at-capacity-rather-than-overrunning-test
  ;; reserve clamps, so a long-lived emitter parks at capacity instead of
  ;; writing past the end of the columns.
  (let [small (assoc trickle-decl :capacity 8)]
    (is (= 8 (.size (ticked small 200))))))

(deftest a-particle-spawned-this-tick-is-not-also-integrated-this-tick-test
  ;; Update runs before spawn, so the newest particle sits at exactly the
  ;; position its spawn modules wrote for the one frame it is first drawn.
  (let [pc (ticked trickle-decl 3)
        l (layout/build (:attrs trickle-decl) 64)
        y-col (second (layout/column l :position))]
    (is (= 1 (.size pc)))
    (is (= 0.0 (double (aget (.floats pc) (+ (* y-col 64) 0))))
        "a velocity of +1 y would have moved it had integrate run first")))

(deftest continuous-emission-does-not-repeat-one-particle-test
  ;; Draws used to be seeded on the buffer SLOT. That is fine for a burst,
  ;; where the slots are 0..n-1, and wrong for a continuous emitter:
  ;; kill-expired swap-removes, so once the population settles reserve
  ;; returns the same slot forever and every particle spawned from then on
  ;; was an exact copy of the last. Seeded on the spawn ordinal instead.
  (let [varied (-> trickle-decl
                   (assoc :capacity 32
                          :attrs (assoc (:attrs trickle-decl) :size :float))
                   (update :spawn conj {:module :spawn/set :attr :size
                                        :value {:kind :uniform :min 0.1 :max 0.2}})
                   (assoc-in [:spawn 4 :value] 1.0)      ; :lifetime, in seconds
                   (assoc :update [{:module :integrate} {:module :kill-expired}]))
        pc (ticked varied 400)
        l (layout/build (:attrs varied) 32)
        col (long (first (layout/column l :size)))
        sizes (mapv #(aget (.floats pc) (+ (* col 32) (long %))) (range (.size pc)))]
    (is (= 8 (.size pc)) "settled at rate * lifetime")
    (is (< 1 (count (distinct sizes)))
        (str "every live particle has the same size, so the emitter is"
             " drawing one particle over and over: " (pr-str sizes)))))
