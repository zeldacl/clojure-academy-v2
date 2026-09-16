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
            [cn.li.vfx.layout :as layout])
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
