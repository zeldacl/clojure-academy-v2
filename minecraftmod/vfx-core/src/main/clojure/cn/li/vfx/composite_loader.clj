(ns cn.li.vfx.composite-loader
  "vfx-core's thin binding of cn.li.node.composite-loader to real EDN
   resources -- see cn.li.combat.composite-loader for the identical
   pattern on the other domain (both bind the same generic loader to
   cn.li.mcmod.runtime.safe-edn/read-resource!; the duplication is this
   one small wrapper, not the manifest/fail-closed logic itself, which
   stays in node-core so neither domain module needs to depend on the
   other).

   Also registers vfx's own domain-specific expression opcodes
   (currently :vfx/ring-point) that composite bodies may reference via
   {:expr ...} -- see this namespace's ring-point-op."
  (:require [cn.li.mcmod.runtime.safe-edn :as safe-edn]
            [cn.li.node.expr :as expr]
            [cn.li.node.composite-loader :as loader]))

(defn- point-components
  "Extract [x y z] from any of the three vec3 shapes this codebase uses:
   {:x :y :z} (this session's own test fixtures/ops/->v3's primary case),
   positional [x y z], or {:vec3 [x y z]} -- the ONLY shape a real
   ability's :effect/vfx position payload ever carries
   (cn.li.combat.vm/vec3-components, node-core's own :vec3/* expr ops).
   Mirrors ops/->v3's shape handling; this exists separately because
   ring-point-op needs plain doubles to do trig with, not a V3 instance.
   Missing this branch previously meant a real {:vec3 [...]} center
   silently read as (0,0,0) via (:x center) => nil => (or nil 0.0) --
   wrong, not crashing, so no test caught it until traced through what
   shape real content actually sends (see ops.clj's ->v3 fix, the same
   root cause)."
  [point]
  (if (and (map? point) (vector? (:vec3 point)))
    (:vec3 point)
    [(or (:x point) (nth point 0 0.0))
     (or (:y point) (nth point 1 0.0))
     (or (:z point) (nth point 2 0.0))]))

(defn- ring-point-op
  "[center radius index count] -> {:x :y :z} at angle (index/count * 2pi)
   around center in the XZ plane (Y held constant) -- vfx-core's own
   {:x :y :z} vec3 convention (cn.li.vfx.ops/->v3), not node-core's
   {:vec3 [...]} shape, since this feeds straight into :vfx/line's
   :from/:to. Computing this from nested :math/sin/:math/cos/:math/mul/
   :math/add :expr forms directly in EDN is possible but painfully
   verbose for something every ring-shaped effect needs -- exactly what
   cn.li.node.expr/register-op!'s domain-extension point exists for."
  [args _seed]
  (let [[center radius index count] args
        angle (* 2.0 Math/PI (/ (double index) (double (max 1 count))))
        [cx cy cz] (mapv double (point-components center))
        r (double radius)]
    {:x (+ cx (* r (Math/cos angle))) :y cy :z (+ cz (* r (Math/sin angle)))}))

(defn install-expr-ops!
  "Register vfx-core's domain-specific expression opcodes. Idempotent
   (register-op! always overwrites); call before loading any composite
   whose body references one."
  []
  (expr/register-op! :vfx/ring-point ring-point-op))

(defn install!
  "Load and register every :layer :mid composite `manifest-resource`
   lists, and ensure vfx's own expr opcodes are registered first. Returns
   {:registered [...] :errors [...]}, see
   cn.li.node.composite-loader/install!."
  ([manifest-resource]
   (install! manifest-resource safe-edn/read-resource!))
  ([manifest-resource document-loader]
   (install-expr-ops!)
   (loader/install! {:manifest-resource manifest-resource :document-loader document-loader})))
