(ns cn.li.ac.vfx.empty-render-graph-audit-test
  "Phase D of the node-engine/VFX performance plan: an audit, not a fix.
   2 of the 36 ac/vfx-v4/*.edn documents compile to a :render graph that
   is literally start->end with no component node in between -- real
   content, discovered while measuring per-frame VFX cost (an empty
   :render costs nothing to sample, so the per-instance-cost numbers the
   perf plan measured are ~2x lower than they will be once this content
   is filled in).

   Both empty documents are legitimately empty side-channel effects:
   :screen-flash-session's :alpha/:duration-ticks/:color are consumed by
   cn.li.ability.client-vfx-v2's own update-presentation-sidechannels! at
   SIGNAL-DISPATCH time (see that fn's own :screen-flash-session case),
   never through scene sampling -- there is nothing for its :render graph
   to draw. (:camera-fov-session, which looks like the same side-channel
   family, is NOT in the empty set: its :render graph genuinely emits a
   :camera-fov scene op reading ?offset -- confirmed by direct
   compilation below, not assumed from the naming pattern.)

   :blood-retrograde-charge owns the same owner-local walk-speed side channel
   used by the legacy charge effect; :camera-fov-session, which looks like the
   same family, is NOT in the empty set because it genuinely emits a
   :camera-fov scene op.

   This test prevents SILENT accumulation: any newly empty document must be
   explicitly classified into the side-channel set rather than slipping through
   unnoticed."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [cn.li.node.graph-compile :as graph-compile]
            [cn.li.vfx.dsl-vocabulary :as vocab]
            [cn.li.vfx.scene :as scene]))

(def ^:private vfx-v4-resource-root "ac/vfx-v4")

(def ^:private side-channel-stubs
  "Effects whose :render graph is legitimately empty because their data
   is consumed at signal-dispatch time by a client-side side channel,
   never through scene sampling. Adding to this set is a real design
   claim -- point at the specific side-channel consumer, as above."
  #{:screen-flash-session :blood-retrograde-charge})

(def ^:private known-migration-leftover-stubs
  "Reserved for newly discovered V4 migration leftovers. All currently known
   empty graphs are classified as side-channel consumers."
  #{})

(defn- vfx-v4-resource-names []
  (let [root (io/resource vfx-v4-resource-root)]
    (when-not root (throw (ex-info "ac/vfx-v4 resource root not found on classpath" {})))
    (->> (.listFiles (io/file root))
         (filter #(.endsWith (.getName ^java.io.File %) ".edn"))
         (mapv #(.getName ^java.io.File %)))))

(defn- load-vfx-v4-doc [resource-name]
  (edn/read-string (slurp (io/resource (str vfx-v4-resource-root "/" resource-name)))))

(defn- render-instruction-count
  "Total :pure/:cap/etc IR instruction count across `doc`'s compiled
   :render graph -- the same measure a compile-time-only structural check
   on :nodes could approximate, but this asks the REAL compiler, so a
   future graph shape this test's author didn't anticipate still gets
   counted correctly."
  [doc]
  (let [input-types (into {} (map (fn [[k spec]] [k (:type spec)]))
                          (or (:inputs doc) (:parameters doc)))
        {:keys [ir diagnostics]} (graph-compile/compile-vfx!
                                  doc {:vocab vocab/nodes
                                       :capabilities (scene/capabilities-for input-types)
                                       :fns {}}
                                  :throw)]
    (when (seq diagnostics)
      (throw (ex-info "V4 VFX graph compilation failed during empty-render-graph audit"
                      {:id (:id doc) :diagnostics diagnostics})))
    (reduce + (map #(count (:instrs %)) (:blocks ir)))))

(defn- empty-render-graph-ids []
  (into #{}
        (keep (fn [resource-name]
                (let [doc (load-vfx-v4-doc resource-name)]
                  (when (= 1 (render-instruction-count doc)) (:id doc)))))
        (vfx-v4-resource-names)))

(deftest every-empty-render-graph-is-explicitly-classified-test
  (let [empty-ids (empty-render-graph-ids)
        known (set/union side-channel-stubs known-migration-leftover-stubs)
        unclassified (set/difference empty-ids known)]
    (is (empty? unclassified)
        (str "New empty :render graph(s) found, not in side-channel-stubs or "
             "known-migration-leftover-stubs: " unclassified
             " -- classify explicitly (side-channel data flow, or a fresh "
             "V4 migration leftover) rather than letting this slip through"))))

(deftest side-channel-stubs-do-not-overlap-migration-leftovers-test
  (is (empty? (set/intersection side-channel-stubs known-migration-leftover-stubs))))

(deftest camera-fov-session-is-not-a-stub-test
  (testing "the one effect that LOOKS like it belongs in side-channel-stubs
            (same naming pattern, same client-side FOV feature) but is not:
            confirmed by direct compilation, not assumed from the name"
    (is (not (contains? side-channel-stubs :camera-fov-session)))
    (is (not (contains? known-migration-leftover-stubs :camera-fov-session)))
    (is (> (render-instruction-count (load-vfx-v4-doc "camera-fov-session.edn")) 1)
        "camera-fov-session's :render graph genuinely emits a :camera-fov op")))
