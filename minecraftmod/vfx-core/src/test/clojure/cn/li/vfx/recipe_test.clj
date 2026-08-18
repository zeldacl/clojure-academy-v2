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
