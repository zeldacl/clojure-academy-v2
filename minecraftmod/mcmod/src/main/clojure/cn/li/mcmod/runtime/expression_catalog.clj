(ns cn.li.mcmod.runtime.expression-catalog
  "Data examples used by coverage tests and documentation generators.")

(def expression-examples
  {:math/add {:expr :math/add :args [1.0 2.0]}
   :math/sub {:expr :math/sub :args [3.0 1.0]}
   :math/mul {:expr :math/mul :args [2.0 4.0]}
   :math/div {:expr :math/div :args [8.0 2.0]}
   :math/min {:expr :math/min :args [1.0 2.0]}
   :math/max {:expr :math/max :args [1.0 2.0]}
   :math/abs {:expr :math/abs :args [-2.0]}
   :math/sqrt {:expr :math/sqrt :args [9.0]}
   :math/sin {:expr :math/sin :args [0.5]}
   :math/cos {:expr :math/cos :args [0.5]}
   :math/clamp {:expr :math/clamp :args [1.5 0.0 1.0]}
   :math/lerp {:expr :math/lerp :args [10.0 20.0 0.5]}
   :math/lt {:expr :math/lt :args [1.0 2.0]}
   :math/lte {:expr :math/lte :args [1.0 1.0]}
   :math/eq {:expr :math/eq :args [1.0 1.0]}
   :math/gte {:expr :math/gte :args [2.0 1.0]}
   :math/gt {:expr :math/gt :args [2.0 1.0]}
   :math/select {:expr :math/select :args [true 1.0 0.0]}
   :bool/and {:expr :bool/and :args [true true]}
   :bool/or {:expr :bool/or :args [false true]}
   :bool/not {:expr :bool/not :args [false]}
   :vec3/add {:expr :vec3/add :args [{:vec3 [1 2 3]} {:vec3 [4 5 6]}]}
   :vec3/sub {:expr :vec3/sub :args [{:vec3 [4 5 6]} {:vec3 [1 2 3]}]}
   :vec3/scale {:expr :vec3/scale :args [{:vec3 [1 2 3]} 2.0]}
   :vec3/dot {:expr :vec3/dot :args [{:vec3 [1 0 0]} {:vec3 [0 1 0]}]}
   :vec3/cross {:expr :vec3/cross :args [{:vec3 [1 0 0]} {:vec3 [0 1 0]}]}
   :vec3/length {:expr :vec3/length :args [{:vec3 [3 4 0]}]}
   :vec3/normalize {:expr :vec3/normalize :args [{:vec3 [0 1 0]}]}
   :vec3/distance {:expr :vec3/distance :args [{:vec3 [0 0 0]} {:vec3 [1 1 1]}]}
   :random/uniform {:expr :random/uniform :args [0.95 1.05]}
   :random/int {:expr :random/int :args [0 15]}
   :random/chance {:expr :random/chance :args [0.05]}})
