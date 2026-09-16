(ns cn.li.vfx.dsl-vocabulary
  "The surface-DSL scene vocabulary: leaf render/audio/camera nodes only.
   The old two-tier catalog this table replaced (cn.li.vfx.vocabulary +
   cn.li.vfx.final-engine's sampler) is gone -- deleted in the S3/S4
   cutover, this is now the only vocabulary.

   The old sampler's STRUCTURAL nodes -- :vfx/let, :vfx/repeat,
   :vfx/timeline, :vfx/group, :vfx/branch -- have no entry here at all:
   they map directly onto DSL primitives cn.li.node.compile already has
   (let/each/when, with :vfx/group's plain child sequencing simply being
   more statements in the same `:do` body). A scene is an ORDINARY DSL
   program, not a bespoke tree-of-render-nodes -- see cn.li.vfx.scene.

   Every node here is :action-kind (:returns nil): scene sampling has no
   host to query, only pure geometry construction, so a 'call' is really
   'append one draw/audio/camera op to this frame's outbox' -- see
   cn.li.vfx.scene's host, whose :command! is where the construction
   happens (:capability names below double as the emitted op's :kind)."
  (:require [clojure.string :as str]))

(defn- node
  ([params] (node params #{}))
  ([params effects] {:params params :returns nil :effects effects :cost 1}))

(defn- p [] {:type :any})
(defn- p* [type] {:type type})
(defn- opt [type default] {:type type :default default})

;; --- editor palette presentation (:category/:i18n) --------------------
;;
;; Only 12 node ids and none of them namespaced (unlike combat's :ns/name
;; shape), so a per-id table here is both simpler and more precise than
;; deriving from a namespace that does not exist.
(def ^:private category-by-id
  {:ring :geometry :vortex-column :geometry :plasma-body :geometry :teleport-marker :geometry :target-box :geometry :beam :geometry :ray-beam :geometry :ray-fan :geometry :line :geometry
   :quad :geometry :arc :geometry :surround-arc :geometry :emitter :geometry
   :particle-trail :geometry
   :trajectory :geometry
   :audio-one-shot :audio :audio-loop :audio
   :camera-fov :camera :camera-shake :camera :post-process :camera
   :first-person-motion :geometry})

(defn- i18n-for [id] (str "editor.node.vfx." (str/replace (name id) "-" "_")))

(defn- attach-presentation
  [nodes-map]
  (into {} (map (fn [[id spec]]
                  [id (assoc spec :category (get category-by-id id :uncategorized) :i18n (i18n-for id))]))
        nodes-map))

(def ^:private raw-nodes
  {;; --- geometry --------------------------------------------------------
   ;; :alpha is NOT a port of anything the old :vfx/ring/:vfx/beam sampler
   ;; read directly (old final_engine.clj's :vfx/ring case actually reads
   ;; :material :alpha off a dead (:alpha context) that is never set,
   ;; always defaulting to 1.0 -- see ring_fade_audio.edn's (S6) own
   ;; docstring) -- it is the real, working destination the old :vfx/fade
   ;; MODIFIER wrote its computed alpha into via assoc-in on the
   ;; CONSTRUCTED op (fade has no primitive-node analogue in the new flat
   ;; :do sequence, so a faded leaf call now just passes its own computed
   ;; alpha directly).
   :ring
   (node {:center (p* :vec3) :radius (p* :double) :segments (opt :long 16) :color (opt :rgba nil)
         :alpha (opt :double 1.0)})
   ;; Main Storm Wing's four TornadoEffect columns. The renderer owns the
   ;; randomized ring stack, scrolling texture and column transform; the
   ;; skill graph supplies only authoritative pose and lifecycle inputs.
   :vortex-column
   (node {:base (p* :vec3) :orientation (p* :any) :radius (p* :any)
         :seed (p* :long) :alpha (p* :double) :height (p* :double)
         :spacing (p* :any) :size (opt :double 0.16)
         :displacement-scale (opt :double 2.0)
         :age (opt :double 0.0)
         :fade-ratio (opt :double 1.0) :source-player-id (opt :any nil)})
   :plasma-body
   (node {:center (p* :vec3) :alpha (p* :double)
          :age (opt :double 0.0) :seed (opt :long 0)})
   ;; Main teleport skills render a 7-frame textured biped marker.  The
   ;; neutral renderer owns the model geometry; the graph supplies only its
   ;; authoritative anchor, tint, facing and age.
   :teleport-marker
   (node {:position (p* :vec3) :direction (opt :vec3 [0.0 0.0 1.0])
          :color (p* :any) :age (opt :double 0.0)})
   :target-box
   (node {:center (p* :vec3) :width (p* :double) :height (p* :double)
          :color (opt :rgba nil)})
   :beam
   (node {:start (p* :vec3) :end (p* :vec3) :layers (opt :any nil) :grow-ticks (opt :long 0)
         :alpha (opt :double 1.0)})
   ;; VecAccel's first-person parabola. Keep the physics inputs explicit so
   ;; the V4 compiler rejects an incomplete trajectory at build time instead
   ;; of allowing a nil to reach the renderer during sampling.
   :trajectory
   (node {:origin (p* :vec3) :look-dir (p* :vec3) :init-vel (p* :vec3)
          :dt (p* :double) :drag (p* :double) :gravity (p* :double)
          :lateral-offset (p* :double) :vertical-offset (p* :double)
          :forward-offset (p* :double) :width (p* :double)
          :segments (p* :long) :can-perform? (p* :boolean)
          :style (p* :any)})
   :ray-fan
   (node {:origin (p* :vec3) :direction (p* :vec3)
          :count (p* :long) :length (p* :double)
          :yaw-range-degrees (p* :any) :pitch-range-degrees (p* :any)
          :seed (p* :long) :grow-ticks (opt :long 0)
          :age (opt :double 0.0) :life-ticks (p* :long)
          :style (opt :any nil)})
   :ray-beam
   (node {:start (p* :vec3) :end (p* :vec3) :style (opt :any nil) :grow-ticks (opt :long 0)})
   :line
   ;; No :color: the frame arm forwards :material and nothing else, so a
   ;; colour set here reached no renderer.
   (node {:from (p* :vec3) :to (p* :vec3) :material (opt :any nil)})
   :quad
   (node {:geometry (p) :material (opt :any nil)})
   ;; Zigzag lightning bolt (main arc-gen / EntityArc). Expanded to textured
   ;; segment quads by cn.li.platform.neutral.arc-geometry — not a no-op emitter.
   :arc
   (node {:start (p* :vec3) :end (p* :vec3)
          ;; Optional fan-out endpoints are used by chained strike effects:
          ;; one declarative arc node can draw the impact point to each target.
          :end-points (opt :any nil)
          :pattern (opt :any :weak) :seed (opt :long 0)
          ;; Universal scene :age is :double (cn.li.vfx.scene); coerce in
          ;; frame/arc-geometry. :arc-life-ticks matches main EntityArc ttl.
          ;; :bolt-count — main ArcGen spawns 3 EntityArcs; default 1 elsewhere.
          :age (opt :double 0.0)
          :arc-life-ticks (opt :any 0)
          :duration-ticks (opt :any 0)
          :bolt-count (opt :any 1)
          :life-ratio (opt :double 0.0) :alpha (opt :double 1.0)
          :hand-origin? (opt :boolean false)
          :source-player-id (opt :any nil)})
   ;; EntitySurroundArc-equivalent short sparks used by current-charging.
   ;; The neutral renderer owns the geometry so skill graphs only describe
   ;; the target body and lifecycle, without embedding client code.
   :surround-arc
   (node {:origin (p* :vec3)
          :mode (opt :resource-id :block)
          :good? (opt :boolean true)
          :block-pos (opt :any nil)
          :block-bounds (opt :any nil)
          :age (opt :double 0.0)
          :seed (opt :long 0)
          :count (opt :long nil)
          :source-player-id (opt :any nil)})
   ;; :vfx/emitter is a single
   ;; declarative "spawn an emitter here" draw-batch op per sample, not a
   ;; per-particle simulation -- the actual particle stepping happens
   ;; client-side off the :particle description, same as every other
   ;; scene leaf here. Unrelated to cn.li.vfx.compile's Niagara module-
   ;; stack machinery (that proves out real CPU particle simulation for
   ;; FUTURE richer content; none of the 36 real ac/vfx-v4 effects need it,
   ;; since none of them do per-particle server/headless simulation).
   :emitter
   (node {:anchor (p* :vec3) :rate-per-tick (opt :double nil) :limit (opt :long nil)
         :chance (opt :double nil) :anchor-offset-y (opt :double 0.0) :particle (opt :any nil)
         :hand-origin? (opt :boolean false) :source-player-id (opt :any nil)})
   ;; Main shift-teleport uses a bounded burst of camera-facing particles
   ;; distributed along a segment. Keeping all inputs explicit lets V4 reject
   ;; incomplete trail definitions during compilation.
   :particle-trail
   (node {:start (p* :vec3) :end (p* :vec3)
          :count-limit (p* :long) :spacing (p* :any)
          :radius (p* :any) :size (p* :any)
          :velocity (p* :any) :texture (p* :string)
          :alpha (p* :any) :life-ticks (p* :long)
          :fade-in (p* :long) :fade-out (p* :long)
          :age (opt :double 0.0) :seed (opt :long 0)})

   ;; --- audio -------------------------------------------------------------
   :audio-one-shot
   (node {:sound-id (p* :string) :volume (opt :double 1.0) :pitch (opt :double 1.0) :position (p* :vec3)})
   :audio-loop
   ;; No :instance-key: ->java-frame assocs the SAMPLE's own instance key
   ;; onto every op, so one set here was always overwritten -- identity
   ;; belongs to the envelope, not the payload.
   ;;
   ;; No :stop-on-destroy? either. A loop is re-synced by key every frame
   ;; it is sampled, so destroying the instance stops it by construction;
   ;; the flag selected nothing and appeared nowhere in the renderer.
   (node {:sound-id (p* :string) :volume (opt :double 1.0) :pitch (opt :double 1.0)
         :position (p* :vec3) :looping? (opt :boolean true)})

   ;; --- camera/post ---------------------------------------------------------
   :camera-fov
   ;; No :duration-ticks: VfxOutput has no slot for one, and a scene is
   ;; re-sampled every render frame, so a ramp is expressed by computing
   ;; :value from the effect's own age rather than by declaring a span.
   (node {:value (p* :double)})
   :camera-shake
   (node {:amplitude (p* :double) :duration (p* :double)})
   :post-process
   (node {:effect (p* :keyword)})

   ;; First-person hand pose emitted by the V4 motion composite.
   :first-person-motion
   (node {:stage (p* :resource-id)
          :phase-ticks (p* :long)
          :duration-ticks (p* :long)
          :curves (p)})})

(def nodes (attach-presentation raw-nodes))
