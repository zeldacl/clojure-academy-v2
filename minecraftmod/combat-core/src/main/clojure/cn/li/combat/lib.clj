(ns cn.li.combat.lib
  "Combat-core's reusable :defn function library: EDN resources under
   cn/li/combat/lib/*.edn, each a single cn.li.node.surface :defn document,
   loaded and parsed into the {fn-id normalized-doc} shape
   cn.li.combat.run/compile-doc!'s :fns argument expects.

   These are the new-DSL replacements for the old cn.li.combat.composites
   resources (combat-core/src/main/resources/cn/li/combat/composites/):
   12 of the old 17 are ported here (straightforward query/action/loop
   shapes -- see each file's own docstring for what changed and why); the
   3 callback-typed ones (combat/charged-area-damage, combat/projectile-
   reflection-scan, combat/impact-strike) are inlined at their few call
   sites during ability conversion instead of given first-class
   higher-order function support, and the 2 confirmed-unused ones
   (fx/lightning-strike, combat/release-with-cost) are dropped entirely.

   An explicit filename list, not a classpath directory scan: this
   namespace is the only place that needs to enumerate the library, a
   directory-listing abstraction (see cn.li.ac.discovery.scanner's jar-vs-
   filesystem handling for how much heavier that gets) buys nothing at 12
   files, and an explicit list fails loudly (a typo'd filename throws
   immediately at load) instead of silently loading fewer functions than
   intended."
  (:require [clojure.java.io :as io]
            [cn.li.node.surface :as surface]))

(def ^:private resource-names
  ["target_directional_destination.edn"
   "target_raycast_destination.edn"
   "target_hold_destination.edn"
   "target_penetration_destination.edn"
   "apply_break_budget.edn"
   "area_damage.edn"
   "beam_strike.edn"
   "break_area.edn"
   "random_break.edn"
   "teleport_group.edn"
   "radial_impulse_motion.edn"
   "wave_plan.edn"])

(defn- read-fn-doc [resource-name]
  (let [path (str "cn/li/combat/lib/" resource-name)
        resource (io/resource path)]
    (when-not resource
      (throw (ex-info "missing combat-core lib resource" {:resource path})))
    (surface/parse (slurp resource))))

(def fns
  "fn-id -> normalized :defn doc, for every file in resource-names. Built
   once at namespace load, not lazily per-compile: the whole library is
   small and every real ability compile needs the whole map anyway (a
   :defn can call another :defn, e.g. target/hold-destination calling
   target/raycast-destination -- see that file's own docstring)."
  (into {} (map (fn [n] (let [doc (read-fn-doc n)] [(:id doc) doc]))) resource-names))
