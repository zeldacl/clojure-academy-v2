(ns cn.li.vfx.ops
  "Pure construction of the platform renderer's own op vocabulary (:kind
   :line/:quad, presentation_world.clj's sort-ops/render-presentation-
   geometry!) -- shared by cn.li.vfx.vm's v2 :vfx/line/:vfx/quad leaf nodes
   and cn.li.vfx.v3-primitives' v3 registrations of the same two
   components, so the op shape is defined exactly once. See R3 (mossy-wren
   plan) and vm.clj's own comment on why these two are the genuinely
   orthogonal render primitives: cn.li.mcmod.math.V3 is a plain Java value
   type with no Minecraft API surface, so vfx-core constructing it directly
   here does not cross verifyVfxDependencyDirection's boundary."
  (:import [cn.li.mcmod.math V3]))

(defn ->v3
  "Accept a {:x :y :z} map, a positional [x y z] vector, or -- the shape
   every real vec3 value actually carries once it crosses from combat-core
   (cn.li.combat.vm/vec3-components, node-core's own :vec3/* expr ops) into
   a VFX payload -- {:vec3 [x y z]}.

   The {:vec3 [...]} branch is not optional: without it, (:x point) is nil
   and this used to fall through to (nth point 0 0.0) -- but a map is
   seqable, so nth walks it as a one-entry seq of MapEntry and returns
   [:vec3 [x y z]] itself, not a number, and (double ...) on that throws
   ClassCastException. :vfx/line/:vfx/quad had never been exercised with a
   real position value (only this session's own {:x :y :z} test fixtures)
   until this was caught, so every real ability's :effect/vfx position
   payload would have crashed the instant content actually called them."
  ^V3 [point]
  (if (and (map? point) (vector? (:vec3 point)))
    (let [[x y z] (:vec3 point)]
      (V3. (double x) (double y) (double z)))
    (V3. (double (or (:x point) (nth point 0 0.0)))
         (double (or (:y point) (nth point 1 0.0)))
         (double (or (:z point) (nth point 2 0.0))))))

(defn line-op [{:keys [from to color]}]
  {:kind :line :p1 (->v3 from) :p2 (->v3 to) :color color})

(defn quad-op [{:keys [p0 p1 p2 p3 u0 u1 v0 v1 color texture]}]
  {:kind :quad :p0 (->v3 p0) :p1 (->v3 p1) :p2 (->v3 p2) :p3 (->v3 p3)
   :u0 (double (or u0 0.0)) :u1 (double (or u1 1.0))
   :v0 (double (or v0 0.0)) :v1 (double (or v1 1.0))
   :color color :texture texture})

(defn ops-batch
  "Wrap one or more ops into the exact {:primitive :mesh :variant :ops
   :payload [{:ops [...]}]} shape :draw-batch! already understands."
  [stage ops]
  {:stage stage :primitive :mesh :material :presentation-world :variant :ops
   :layout-version 1 :count (count ops) :sort-mode :stable :payload [{:ops (vec ops)}]})
