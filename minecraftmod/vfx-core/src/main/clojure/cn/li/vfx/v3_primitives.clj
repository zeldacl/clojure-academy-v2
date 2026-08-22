(ns cn.li.vfx.v3-primitives
  "v3 registration for vfx-core's genuinely orthogonal render primitives
   (R3, mossy-wren plan; NODE_LANGUAGE.md section 9). :impl is a pure data-
   shaping function -- given typed, already-resolved inputs, it returns the
   op map cn.li.vfx.ops already produces for the v2 :vfx/line/:vfx/quad
   leaf nodes (cn.li.vfx.vm), reusing the exact same tested construction
   logic rather than a second copy of it. A future vfx-core v3 sampler
   collects the ops each node in one graph evaluation produces and wraps
   them into the single :mesh/:ops batch cn.li.vfx.ops/ops-batch already
   knows how to build -- that accumulation loop is not written yet (same
   category of deferred work as combat-core's source-node execution wiring,
   pushed to R4 there for the same reason: nothing needs it to actually run
   until real composite content exists).

   ADDITIVE ONLY at this revision: the v2 registry (cn.li.vfx.components)
   and vm.clj's :vfx/line/:vfx/quad defmethods are untouched and still
   serve every existing effect document; this is a parallel v3 vocabulary
   registered on node-core, not a replacement."
  (:require [cn.li.node.descriptor :as node]
            [cn.li.vfx.ops :as ops]))

(defn install!
  "Register the v3 render primitives. Call once per registry lifetime,
   before node/freeze!."
  []
  (node/register-primitive!
   {:id :vfx/line :revision 1 :category :render
    :doc "A single line segment, the platform renderer's own primitive shape."
    :inputs {:from {:type :vec3} :to {:type :vec3} :color {:type :color}}
    :outputs {:op {:type :render-op}} :effects #{:emit}
    :impl (fn [inputs _ctx] {:op (ops/line-op inputs)})})
  (node/register-primitive!
   {:id :vfx/quad :revision 1 :category :render
    :doc "A single quad (p0/p1 at the start edge, p2/p3 at the end edge; u runs along the shape, v across its width)."
    :inputs {:p0 {:type :vec3} :p1 {:type :vec3} :p2 {:type :vec3} :p3 {:type :vec3}
             :u0 {:type :double :default 0.0} :u1 {:type :double :default 1.0}
             :v0 {:type :double :default 0.0} :v1 {:type :double :default 1.0}
             :color {:type :color} :texture {:type :string :default nil}}
    :outputs {:op {:type :render-op}} :effects #{:emit}
    :impl (fn [inputs _ctx] {:op (ops/quad-op inputs)})}))
