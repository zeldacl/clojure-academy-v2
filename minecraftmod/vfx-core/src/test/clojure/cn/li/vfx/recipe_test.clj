(ns cn.li.vfx.recipe-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.vfx.components :as components]
            [cn.li.vfx.recipe :as recipe])
  (:import [cn.li.mcmod.runtime.effect CompiledProgram]))

(deftest effect-graph-validates-recursively-and-compiles-root
  (components/reset-for-test!)
  (let [effect {:schema-version 1 :kind :vfx-effect :id :railgun-beam
                :revision 1 :lifecycle :transient
                :graph {:component :vfx/timeline :duration-ticks 3
                        :children [{:at 0
                                    :node {:component :vfx/beam
                                           :start {:ref [:input :start]}
                                           :end {:ref [:input :end]}
                                           :layers []}}]}}
        compiled (recipe/compile-effect effect)]
    (is (:compiled? compiled))
    ;; Children are embedded in the root graph constant and are interpreted
    ;; by the VFX VM; they must not become independent top-level executions.
    (is (= 1 (count (:compiled-ir compiled))))
    (is (instance? CompiledProgram (:compiled-program compiled)))))

(deftest composite-components-expand-before-validation
  (components/reset-for-test!)
  (let [composite {:schema-version 1
                   :kind :composite
                   :id :vfx/test-beam
                   :revision 1
                   :inputs {:start {:type :vec3}
                            :end {:type :vec3}}
                   :body {:component :vfx/timeline
                          :duration-ticks 2
                          :children [{:at 0
                                      :node {:component :vfx/beam
                                             :start {:ref [:input :start]}
                                             :end {:ref [:input :end]}
                                             :layers []}}]}}
        effect {:schema-version 1 :kind :vfx-effect :id :composite-test
                :revision 1 :lifecycle :transient
                :graph {:component :vfx/test-beam
                        :start {:ref [:input :start]}
                        :end {:ref [:input :end]}}}
        compiled (recipe/compile-effect effect {:composites
                                                {:vfx/test-beam composite}})]
    (is (= :vfx/timeline (get-in compiled [:graph :component])))
    (is (= :vfx/beam (get-in compiled [:graph :children 0 :node :component])))
    (is (= 1 (count (:compiled-ir compiled))))))

(deftest model-marker-contract-is-compiled-as-a-generic-node
  (components/reset-for-test!)
  (let [part {:hw 0.25 :hh 0.25 :hd 0.25 :cx 0.0 :cy 0.25
              :front [0.0 0.25 0.0 0.25]
              :back [0.25 0.5 0.0 0.25]
              :right [0.5 0.75 0.0 0.25]
              :left [0.75 1.0 0.0 0.25]
              :top [0.0 0.25 0.25 0.5]
              :bottom [0.25 0.5 0.25 0.5]}
        effect {:schema-version 1 :kind :vfx-effect :id :model-marker-test
                :revision 1 :lifecycle :session
                :graph {:component :vfx/model-marker
                        :anchor {:vec3 [0.0 0.0 0.0]}
                        :texture-pattern "generic/%d.png"
                        :frame-count 1
                        :frame-period-ticks 2.5
                        :parts [part]
                        :color [188 252 238 255]
                        :facing :camera}}
        compiled (recipe/compile-effect effect)]
    (is (:compiled? compiled))
    (is (= :vfx/model-marker (get-in compiled [:graph :component])))
    (is (= 1 (count (:compiled-ir compiled))))))
