(ns cn.li.vfx.dsl-vocabulary
  "The new surface-DSL scene vocabulary: leaf render/audio/camera nodes
   only. Additive alongside the old cn.li.vfx.vocabulary (unchanged, still
   live via cn.li.vfx.final-engine's sampler -- see the redesign plan's
   staging notes).

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
   happens (:capability names below double as the emitted op's :kind).")

(defn- node
  ([params] (node params #{}))
  ([params effects] {:params params :returns nil :effects effects :cost 1}))

(defn- p [] {:type :any})
(defn- p* [type] {:type type})
(defn- opt [type default] {:type type :default default})

(def nodes
  {;; --- geometry --------------------------------------------------------
   :ring
   (node {:center (p* :vec3) :radius (p* :double) :segments (opt :long 16) :color (opt :any nil)})
   :beam
   (node {:start (p* :vec3) :end (p* :vec3) :layers (opt :any nil) :grow-ticks (opt :long 0)})
   :ray-beam
   (node {:start (p* :vec3) :end (p* :vec3) :style (opt :any nil) :grow-ticks (opt :long 0)})
   :line
   (node {:from (p* :vec3) :to (p* :vec3) :color (opt :any nil) :material (opt :any nil)})
   :quad
   (node {:geometry (p) :material (opt :any nil)})

   ;; --- audio -------------------------------------------------------------
   :audio-one-shot
   (node {:sound-id (p* :string) :volume (opt :double 1.0) :pitch (opt :double 1.0) :position (p* :vec3)})
   :audio-loop
   (node {:sound-id (p* :string) :volume (opt :double 1.0) :pitch (opt :double 1.0) :position (p* :vec3)
         :instance-key (opt :any nil) :stop-on-destroy? (opt :boolean true)})

   ;; --- camera/post ---------------------------------------------------------
   :camera-fov
   (node {:value (p* :double) :duration-ticks (opt :long 0)})
   :camera-shake
   (node {:amplitude (p* :double) :duration (p* :double)})
   :post-process
   (node {:effect (p* :keyword)})})
