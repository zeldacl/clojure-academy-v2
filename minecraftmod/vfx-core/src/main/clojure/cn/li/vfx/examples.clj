(ns cn.li.vfx.examples
  "Typed examples used by build-time coverage checks.")

(def final-effect-examples
  [{:id :example-line
    :lifecycle :transient
    :parameters [{:name :duration-ticks :type :tick}]
    :primitives #{:line}}
   {:id :example-quad
    :lifecycle :persistent
    :parameters [{:name :alpha :type :ratio}]
    :primitives #{:quad}}])
