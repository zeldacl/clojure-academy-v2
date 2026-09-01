(ns cn.li.combat.kernels
  "Internal :layer :kernel execution boundaries for the terrain/motion/beam
   primitives whose real work happens in a trusted host capability, not a
   plain descriptor :impl. Relocated verbatim from ac/final_vocabulary.clj's
   kernel-specs -- these are combat-core's own vocabulary, not AC content;
   every kernel here names a :kernel/* capability that
   cn.li.combat.platform's action/query-handlers registry (and
   cn.li.mcmod.runtime.capabilities' derived legal set) must also know
   about. Kernel descriptors are never exported to the visual editor (see
   cn.li.node.descriptor's :kernel handling) and can only be reached from a
   trusted composite lowering.")

(def kernel-specs
  [{:id :kernel/break-area :revision 1 :layer :kernel :visibility :internal
    :inputs {:origin {:type :vec3} :radius {:type :float} :hardness-max {:type :float} :limit {:type :int}}
    :outputs {:broken-count {:type :int}}
    :node-kind :action :execution {:kind :combat-kernel :capability :kernel/terrain-break-area}}
   {:id :kernel/random-break :revision 1 :layer :kernel :visibility :internal
    :inputs {:origin {:type :vec3} :radius {:type :float} :attempts {:type :int} :hardness-max {:type :float} :seed {:type :int}}
    :outputs {:broken-count {:type :int}}
    :node-kind :action :execution {:kind :combat-kernel :capability :kernel/terrain-random-break}}
   {:id :kernel/apply-break-budget :revision 1 :layer :kernel :visibility :internal
    :inputs {:blocks {:type [:list-of :map]} :energy {:type :float} :limit {:type :int} :drop-chance {:type :float}}
    :outputs {:remaining-energy {:type :float}}
    :node-kind :action :execution {:kind :combat-kernel :capability :kernel/terrain-apply-break-budget}}
   {:id :kernel/radial-impulse :revision 1 :layer :kernel :visibility :internal
    :inputs {:center {:type :vec3} :radius {:type :float} :speed {:type :float} :limit {:type :int}}
    :outputs {:affected-count {:type :int}}
    :node-kind :action :execution {:kind :combat-kernel :capability :kernel/motion-radial-impulse}}
   {:id :kernel/terrain-wave-plan :revision 1 :layer :kernel :visibility :internal
    :inputs {:origin {:type :vec3} :direction {:type :vec3} :initial-energy {:type :float} :max-iterations {:type :int} :seed {:type :int}
             :spread {:type :map} :energy-cost {:type :map} :block-transforms {:type :map}
             :mastery {:type :float} :mastery-threshold {:type :float} :mastery-radius {:type :int}
             :mastery-hardness-cap {:type :float} :ground-break-probability {:type :float}
             :drop-probability {:type :float} :launch-base {:type :float} :launch-span {:type :float}
             :entity-search-radius {:type :float}}
    :outputs {:affected-blocks {:type [:list-of :map]} :transforms {:type [:list-of :map]} :broken-blocks {:type [:list-of :map]} :mastery-breaks {:type [:list-of :map]} :entities {:type [:list-of :map]}}
    :node-kind :query :execution {:kind :combat-kernel :capability :kernel/terrain-wave-plan}}
   {:id :kernel/trace-beam :revision 1 :layer :kernel :visibility :internal
    :inputs {:origin {:type :vec3} :trace-origin {:type :any, :default nil} :direction {:type :vec3} :length {:type :float} :visual-length {:type :any, :default nil} :radius {:type :float} :query-radius {:type :any, :default nil} :entity-limit {:type :int, :default 256} :damage {:type :float, :default 0.0} :damage-type {:type :resource-id, :default :generic} :block-limit {:type :int, :default 4096} :reflection-policy {:type :any, :default nil} :step {:type :any, :default nil}}
    :outputs {:beam {:type :map}}
    :node-kind :query :execution {:kind :combat-kernel :capability :kernel/trace-beam}}])
