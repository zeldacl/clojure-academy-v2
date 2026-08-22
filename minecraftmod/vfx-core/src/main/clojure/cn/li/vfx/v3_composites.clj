(ns cn.li.vfx.v3-composites
  "The first real :layer :mid vfx composite (R3, mossy-wren plan step 5):
   :vfx.fx/charge-ring, a faithful decomposition of the old v2
   :vfx/charge-ring semantic node into node-core primitives.

   Not a golden-op equivalence rewrite -- there is no working reference to
   preserve. The old defmethod (cn.li.vfx.vm) emits {:primitive :line
   :variant :charge-ring ...}, the same unrendered variant-tagged shape R3
   step 1 diagnosed for every other pre-existing leaf node; it has never
   drawn anything on screen. This composite reuses the SAME progress/pulse/
   radius arithmetic (verified field-for-field against the old defmethod)
   but emits it as a real ring of :vfx/line segments via :vfx/repeat, so it
   is the first version of this effect that can actually render.

   Registers one small domain-specific expression opcode,
   :vfx/ring-point, via cn.li.node.expr/register-op! -- computing a point
   on a circle from nested :math/sin/:math/cos/:math/mul/:math/add :expr
   forms directly in EDN is possible but painfully verbose for something
   every ring-shaped effect needs; this is exactly the extension point
   register-op! exists for (a domain-owned opcode dispatched by the same
   shared evaluator, not a competing expression language)."
  (:require [cn.li.node.descriptor :as node]
            [cn.li.node.expr :as expr]))

(defn- ring-point-op
  "[center radius index count] -> {:x :y :z} at angle (index/count * 2pi)
   around center in the XZ plane (Y held constant) -- the vfx-core :vec3
   convention (see cn.li.vfx.ops/->v3), not node-core's {:vec3 [...]}
   shape, since this feeds straight into :vfx/line's :from/:to."
  [args _seed]
  (let [[center radius index count] args
        angle (* 2.0 Math/PI (/ (double index) (double (max 1 count))))
        cx (double (or (:x center) 0.0)) cy (double (or (:y center) 0.0)) cz (double (or (:z center) 0.0))
        r (double radius)]
    {:x (+ cx (* r (Math/cos angle))) :y cy :z (+ cz (* r (Math/sin angle)))}))

(defn install!
  "Register :vfx/ring-point and the :vfx.fx/charge-ring composite. Call
   once per registry lifetime, before node/freeze! (and after
   cn.li.vfx.v3-primitives/install!, which this composite's body depends
   on for :vfx/repeat/:vfx/line)."
  []
  (expr/register-op! :vfx/ring-point ring-point-op)
  (node/register-composite!
   {:id :vfx.fx/charge-ring :revision 1 :layer :mid
    :doc "A radial charge indicator: a ring of :segments line points whose radius grows with :charge-ticks/:max-charge-ticks and pulses at :pulse-frequency/:pulse-amplitude -- the same formula the old v2 :vfx/charge-ring used, now emitting real geometry."
    :inputs {:center {:type :vec3}
             :charge-ticks {:type :double} :max-charge-ticks {:type :double :default 1.0}
             :segments {:type :long :default 24}
             :base-radius {:type :double :default 0.0} :radius-growth {:type :double :default 0.0}
             :pulse-amplitude {:type :double :default 0.0} :pulse-frequency {:type :double :default 0.0}
             :color {:type :color}}
    :outputs {}
    :body
    {:component :vfx/let
     :bindings
     {:progress {:expr :math/clamp
                 :args [{:expr :math/div :args [{:ref [:input :charge-ticks]} {:ref [:input :max-charge-ticks]}]}
                        0.0 1.0]}
      :pulse {:expr :math/mul
              :args [{:ref [:input :pulse-amplitude]}
                     {:expr :math/sin :args [{:expr :math/mul :args [{:ref [:input :pulse-frequency]} {:ref [:input :charge-ticks]}]}]}]}}
     :child
     {:component :vfx/let
      :bindings {:radius {:expr :math/add
                          :args [{:ref [:input :base-radius]}
                                 {:expr :math/add
                                  :args [{:expr :math/mul :args [{:ref [:input :radius-growth]} {:ref [:input :progress]}]}
                                         {:ref [:input :pulse]}]}]}}
      :child
      {:component :vfx/repeat :count {:ref [:input :segments]} :index-as :i
       :body
       {:component :vfx/line
        :from {:expr :vfx/ring-point
               :args [{:ref [:input :center]} {:ref [:input :radius]} {:ref [:input :i]} {:ref [:input :segments]}]}
        :to {:expr :vfx/ring-point
             :args [{:ref [:input :center]} {:ref [:input :radius]}
                    {:expr :math/add :args [{:ref [:input :i]} 1]} {:ref [:input :segments]}]}
        :color {:ref [:input :color]}}}}}}))
