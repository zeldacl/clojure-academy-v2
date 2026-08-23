(ns cn.li.combat.composite-loader
  "combat-core's thin binding of cn.li.node.composite-loader to real EDN
   resources: supplies cn.li.mcmod.runtime.safe-edn/read-resource! as the
   default document-loader (node-core itself cannot depend on mcmod, so it
   takes the reader as an injected parameter -- see that namespace's
   docstring), so callers only need to name a manifest resource path.

   Combat's actual :layer :mid composite content (the reusable component
   library any ability document can call into) lives as data under AC's
   resource tree (ac/src/main/resources/ac/combat/composites_v3/), the
   same split the pre-existing v2 system already uses for its own
   :composite documents (ac/.../components/*.edn, loaded by
   cn.li.combat.recipe/load-composites!) -- combat-core owns the loading
   engine, AC owns the content."
  (:require [clojure.string :as str]
            [cn.li.mcmod.runtime.safe-edn :as safe-edn]
            [cn.li.node.expr :as expr]
            [cn.li.node.composite-loader :as loader]))

(defn- launch-op
  "[look speed pitch-offset] -> a launch-velocity {:vec3 [...]} pointed
   along `look`'s horizontal heading, pitched by `pitch-offset` radians
   relative to look's own current pitch, at magnitude `speed`. Faithful
   port of v2's own (non-shared) expr evaluator's :vec3/launch opcode
   (cn.li.combat.vm/evaluate) -- a ballistic launch-velocity helper real
   content reaches for -- combat-only, so it registers through node-core's
   domain-extension point rather than living in node-core's shared
   baseline."
  [args _seed]
  (let [[look-x look-y look-z] (expr/vec3-components (nth args 0))
        speed (double (nth args 1))
        pitch-offset (double (nth args 2))
        horiz-len (Math/sqrt (+ (* (double look-x) (double look-x))
                                (* (double look-z) (double look-z))))
        safe-h (if (pos? horiz-len) horiz-len 1.0)
        current-pitch (Math/atan2 (- (double look-y)) safe-h)
        pitch (+ current-pitch pitch-offset)
        cos-p (Math/cos pitch)
        sin-p (Math/sin pitch)
        hx (/ (double look-x) safe-h)
        hz (/ (double look-z) safe-h)]
    {:vec3 [(* cos-p hx speed) (- (* sin-p speed)) (* cos-p hz speed)]}))

(defn- parse-status-amplifier
  "Real content encodes a status effect as a single colon-delimited string,
   \"effect-name:max-amplifier\" (e.g. \"minecraft:speed:2\" -- the
   namespaced effect id, then a colon, then the amplifier bound); split
   once on the SECOND colon (limit 2) so the namespaced id survives intact
   and only the trailing amplifier is peeled off. A malformed/missing
   amplifier defaults to 0, never negative."
  [value]
  (let [[_ amp-text] (str/split (str value) #":" 2)
        amplifier (try (Long/parseLong (str/trim (or amp-text "0")))
                       (catch NumberFormatException _ 0))]
    (max 0 (long amplifier))))

(defn- status-id-op
  "[\"effect-name:max-amplifier\"] -> :effect-name. Faithful port of v2's
   own (non-shared) expr evaluator's :value/status-id opcode."
  [args _seed]
  (keyword (str/trim (or (first (str/split (str (nth args 0)) #":" 2)) ""))))

(defn- status-max-amplifier-op
  "[\"effect-name:max-amplifier\"] -> max-amplifier (long, floored at 0).
   Faithful port of v2's own (non-shared) expr evaluator's :value/status-
   max-amplifier opcode."
  [args _seed]
  (parse-status-amplifier (nth args 0)))

(defn install-expr-ops!
  "Register combat-core's domain-specific expression opcodes (:vec3/launch,
   :value/status-id, :value/status-max-amplifier). Idempotent (register-
   op! always overwrites); call before loading any composite/ability
   program whose body references one."
  []
  (expr/register-op! :vec3/launch launch-op)
  (expr/register-op! :value/status-id status-id-op)
  (expr/register-op! :value/status-max-amplifier status-max-amplifier-op))

(defn install!
  "Load and register every :layer :mid composite `manifest-resource`
   lists, and ensure combat's own expr opcodes are registered first.
   Returns {:registered [...] :errors [...]}, see
   cn.li.node.composite-loader/install!."
  ([manifest-resource]
   (install! manifest-resource safe-edn/read-resource!))
  ([manifest-resource document-loader]
   (install-expr-ops!)
   (loader/install! {:manifest-resource manifest-resource :document-loader document-loader})))
