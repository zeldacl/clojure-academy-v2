(ns cn.li.vfx.frame
  "New-engine scene op (cn.li.vfx.scene/sample!'s own {:kind k ...fields}
   shape) -> the SAME VfxBatch/VfxOutput/VfxFrame Java carriers the old
   engine's cn.li.vfx.final-client already produces, so ability-runtime's
   compose.clj and every loader's presentation_world_renderer.clj need
   ZERO changes -- only the sampler feeding this translation changes.

   legacy-op below reverse-engineers, from cn.li.vfx.final-engine's own
   sample-node cases (not guessed), the exact :operation/:stage/
   :primitive/:geometry/:material shape final-client's op->java-batch/
   op->java-output already know how to consume. Every new :kind here maps
   to exactly one old :vfx/* case; field-name differences (:layers/:style
   replace old's bare :material for :beam/:ray-beam; :from/:to replace
   :start/:end for :line) are confirmed against real ac/vfx/fx/*.edn
   content, not assumed -- cn.li.platform.neutral.vfx-render-plan's own
   line-ops already accepts :from/:to via its own fallback key chain.

   :emitter is a deliberate exception: its old :vfx/emitter case produces
   geometry with no :p0..:p3 corners, and vfx-render-plan's quad-ops
   requires all four -- so :vfx/emitter has never drawn anything through
   the currently-live old engine either (confirmed by reading quad-ops).
   legacy-op reproduces the SAME (currently a no-op) shape for :emitter,
   which is behavioral parity with today's live rendering, not a
   regression -- fixing it for real is a separate, unrelated follow-up."
  (:import [java.util ArrayList]
           [cn.li.mcmod.runtime.vfx VfxBatch VfxFrame VfxOutput VfxOutputKind VfxRenderStage]))

(def ^:private stage->java
  {:world-translucent VfxRenderStage/WORLD_TRANSLUCENT
   :world-additive VfxRenderStage/WORLD_ADDITIVE
   :world-after-translucent VfxRenderStage/WORLD_AFTER_TRANSLUCENT
   :first-person VfxRenderStage/FIRST_PERSON
   :screen VfxRenderStage/SCREEN})

(defn- legacy-op [{:keys [kind] :as op}]
  (case kind
    :ring {:operation :draw-batch :stage :world-after-translucent :primitive :line
           :geometry {:kind :ring :center (:center op) :radius (:radius op) :segments (:segments op)}
           :material {:color (:color op) :alpha (double (or (:alpha op) 1.0))}}
    :beam {:operation :draw-batch :stage :world-after-translucent :primitive :line
           :geometry {:kind :beam :start (:start op) :end (:end op)}
           :material (assoc (or (:layers op) {}) :alpha (double (or (:alpha op) 1.0)))}
    :ray-beam {:operation :draw-batch :stage :world-after-translucent :primitive :line
               :geometry {:kind :beam :start (:start op) :end (:end op)}
               :material (:style op)}
    :line {:operation :draw-batch :stage :world-after-translucent :primitive :line
           :geometry {:from (:from op) :to (:to op)}
           :material (:material op)}
    :quad {:operation :draw-batch :stage :world-after-translucent :primitive :quad
           :geometry (:geometry op) :material (:material op)}
    :emitter {:operation :draw-batch :stage :world-translucent :primitive :quad
              :geometry {:kind :emitter :anchor (:anchor op) :rate-per-tick (:rate-per-tick op)
                        :limit (:limit op) :particle (:particle op)}
              :material {:particle (:particle op)}}
    :audio-one-shot {:operation :audio :stage :audio :sound-id (:sound-id op)
                      :volume (:volume op) :pitch (:pitch op) :position (:position op)}
    :audio-loop {:operation :audio :stage :audio :sound-id (:sound-id op)
                 :volume (:volume op) :pitch (:pitch op) :position (:position op)}
    :camera-fov {:operation :camera-fov :stage :camera :value (:value op)}
    :camera-shake {:operation :camera-shake :stage :camera
                   :amplitude (:amplitude op) :duration (:duration op)}
    :post-process {:operation :post-process :stage :post :effect (:effect op)}
    nil))

(defn- op->java-batch ^VfxBatch [op]
  (VfxBatch. (or (get stage->java (:stage op)) VfxRenderStage/WORLD_TRANSLUCENT)
             (int (hash (or (:material op) :default)))
             (name (or (:primitive op) :line))
             0
             nil
             (or (:payload op) op)))

(defn- op->java-output ^VfxOutput [op]
  (let [kind (case (:operation op)
               :audio VfxOutputKind/AUDIO
               (:camera-fov :camera-shake) VfxOutputKind/CAMERA
               :post-process VfxOutputKind/SCREEN
               nil)]
    (when kind
      (VfxOutput. kind (int (hash (or (:sound-id op) (:effect op) :none)))
                  (float (or (:volume op) (:value op) (:amplitude op) 0.0))
                  (some-> (or (:sound-id op) (:effect op)) str)))))

(defn ->java-frame
  "frame-id, resource-generation, cn.li.vfx.runtime/sample-frame!'s own
   output ({instance-key {:scene [op...] :emitters [...]}}) -> a VfxFrame.
   Only :scene ops feed batches/outputs today -- :emitters (the real
   Niagara particle-buffer path, cn.li.vfx.compile) has no consumer here
   yet because no real ac/vfx/fx/*.edn effect declares one (dsl-
   vocabulary.clj's own docstring: none of the 36 need it); wiring
   ParticleColumns into a VfxBatch is separate, unrelated follow-up work
   for whenever real content actually needs it."
  ^VfxFrame [frame-id resource-generation sampled]
  (let [batches (ArrayList.) outputs (ArrayList.)]
    (doseq [[_ {:keys [scene]}] sampled
            raw-op scene]
      (when-let [legacy (legacy-op raw-op)]
        (if (= :draw-batch (:operation legacy))
          (.add batches (op->java-batch legacy))
          (when-let [output (op->java-output legacy)] (.add outputs output)))))
    (VfxFrame. (long frame-id) (long resource-generation) batches outputs)))
