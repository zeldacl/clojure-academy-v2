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
   replace old's bare :material for :beam/:ray-beam — `:layers` may be a
   material map or a V4 vector of layer maps; :from/:to replace
   :start/:end for :line) are confirmed against real ac/vfx-v4/*.edn
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
           [cn.li.mcmod.runtime.vfx VfxBatch VfxFrame VfxOutput VfxOutputKind VfxRenderStage ParticleColumns]))

(def ^:private stage->java
  {:world-translucent VfxRenderStage/WORLD_TRANSLUCENT
   :world-additive VfxRenderStage/WORLD_ADDITIVE
   :world-after-translucent VfxRenderStage/WORLD_AFTER_TRANSLUCENT
   :first-person VfxRenderStage/FIRST_PERSON
   :screen VfxRenderStage/SCREEN})

(def ^:private default-layer-color [255 255 255 255])

(defn- beam-material
  "V4 beam content (beam-arc-fade, beam-fade-audio) passes `:layers` as a
   sequence of `{ :shape :color ... }` maps. Older/test shapes pass a single
   material map. The live render plan only reads `:color`/`:alpha` today, so
   promote the first layer's color while preserving the full layer list."
  [{:keys [layers alpha]}]
  (let [a (double (or alpha 1.0))]
    (cond
      (nil? layers) {:alpha a}
      (map? layers) (assoc layers :alpha a)
      (sequential? layers)
      (let [layer-vec (vec layers)]
        (when-not (every? map? layer-vec)
          (throw (ex-info "beam :layers sequence entries must be maps"
                          {:layers layers})))
        (cond-> {:layers layer-vec :alpha a}
          (seq layer-vec) (assoc :color (or (:color (first layer-vec))
                                            default-layer-color))))
      :else (throw (ex-info "beam :layers must be a map or a sequence of layer maps"
                            {:value layers})))))

(defn- sample-motion-curve
  "Linearly sample a V4 first-person motion curve at normalized `t`."
  [points t]
  (let [points (->> (if (sequential? points) points [])
                    (keep (fn [point]
                            (when (and (sequential? point)
                                       (>= (count point) 2)
                                       (number? (first point))
                                       (number? (second point)))
                              [(double (first point)) (double (second point))])))
                    (sort-by first)
                    vec)]
    (cond
      (empty? points) 0.0
      (<= t (ffirst points)) (double (second (first points)))
      (>= t (first (last points))) (double (second (last points)))
      :else
      (loop [[[left right] & more] (partition 2 1 points)]
        (if (<= t (first right))
          (let [[t0 v0] left
                [t1 v1] right
                ratio (/ (- t t0) (max 1.0e-9 (- t1 t0)))]
            (+ v0 (* ratio (- v1 v0))))
          (recur more))))))

(defn- first-person-transform
  "Convert the V4 motion payload into the transform map consumed by the
   platform hand renderer.  Keeping interpolation here makes the scene node
   declarative while retaining the old curve semantics and deterministic
   owner/lifecycle handling in the new frame ABI."
  [{:keys [stage phase-ticks duration-ticks curves]}]
  (let [stage (or stage :prepare)
        stage-curves (or (get curves stage)
                         (get curves (keyword (name stage)))
                         {})
        duration (max 1.0 (double (or duration-ticks 1)))
        t (max 0.0 (min 1.0 (/ (double (or phase-ticks 0)) duration)))]
    (into {}
          (map (fn [axis]
                 [axis (sample-motion-curve (get stage-curves axis) t)])
               [:tx :ty :tz :rot-x :rot-y :rot-z]))))
(defn- legacy-op [{:keys [kind] :as op}]
  (case kind
    :ring {:operation :draw-batch :stage :world-after-translucent :primitive :line
           :geometry {:kind :ring :center (:center op) :radius (:radius op) :segments (:segments op)}
           :material {:color (:color op) :alpha (double (or (:alpha op) 1.0))}}
    :beam {:operation :draw-batch :stage :world-after-translucent :primitive :quad
           :geometry {:kind :beam :start (:start op) :end (:end op)
                      :grow-ticks (:grow-ticks op)}
           :material (beam-material op)}
    :ray-beam {:operation :draw-batch :stage :world-after-translucent :primitive :line
               :geometry {:kind :beam :start (:start op) :end (:end op)}
               :material (:style op)}
    :line {:operation :draw-batch :stage :world-after-translucent :primitive :line
           :geometry {:from (:from op) :to (:to op)}
           :material (:material op)}
    :quad {:operation :draw-batch :stage :world-after-translucent :primitive :quad
           :geometry (:geometry op) :material (:material op)}
    :arc {:operation :draw-batch :stage :world-after-translucent :primitive :quad
          :geometry {:kind :arc
                     :start (:start op) :end (:end op)
                     :pattern (or (:pattern op) :weak)
                     :seed (or (:seed op) 0)
                     :age (long (or (:age op) 0))
                     :arc-life-ticks (long (or (:arc-life-ticks op) 0))
                     :duration-ticks (long (or (:duration-ticks op) 0))
                     :bolt-count (long (or (:bolt-count op) 1))
                     :life-ratio (double (or (:life-ratio op) 0.0))
                     :hand-origin? (boolean (:hand-origin? op))
                     :source-player-id (:source-player-id op)}
          :material {:texture "academy:textures/effects/arc/line_segment.png"
                     :color [255 255 255 255]
                     :alpha (double (or (:alpha op) 1.0))}}
    :emitter {:operation :draw-batch :stage :world-translucent :primitive :quad
              :geometry {:kind :emitter :anchor (:anchor op) :rate-per-tick (:rate-per-tick op)
                        :limit (:limit op) :particle (:particle op)}
              :material {:particle (:particle op)}}
    :audio-one-shot {:operation :audio :stage :audio :sound-id (:sound-id op)
                      :volume (:volume op) :pitch (:pitch op) :position (:position op)
                      :looping? false}
    :audio-loop {:operation :audio :stage :audio :sound-id (:sound-id op)
                 :volume (:volume op) :pitch (:pitch op) :position (:position op)
                 :looping? true}
    :camera-fov {:operation :camera-fov :stage :camera :value (:value op)}
    :camera-shake {:operation :camera-shake :stage :camera
                   :amplitude (:amplitude op) :duration (:duration op)}
    :post-process {:operation :post-process :stage :post :effect (:effect op)}
    :first-person-motion {:operation :draw-batch :stage :first-person :primitive :first-person
                         :payload [(first-person-transform op)]}
    nil))

(defn- op->java-batch ^VfxBatch [op]
  (VfxBatch. (or (get stage->java (:stage op)) VfxRenderStage/WORLD_TRANSLUCENT)
             (int (hash (or (:material op) :default)))
             (name (or (:primitive op) :line))
             0
             nil
             (or (:payload op) op)))

(defn- audio-position->xyz [position]
  (let [components (cond
                     (and (map? position)
                          (every? #(number? (get position %)) [:x :y :z]))
                     (mapv #(double (get position %)) [:x :y :z])

                     (and (map? position)
                          (sequential? (:vec3 position)))
                     (:vec3 position)

                     (and (sequential? position) (= 3 (count position)))
                     position

                     :else nil)]
    (if (and (= 3 (count components)) (every? number? components))
      (mapv double components)
      (throw (ex-info "audio output requires a concrete vec3 position"
                      {:position position})))))

(defn- op->java-output ^VfxOutput [op]
  (let [kind (case (:operation op)
               :audio VfxOutputKind/AUDIO
               (:camera-fov :camera-shake) VfxOutputKind/CAMERA
               :post-process VfxOutputKind/SCREEN
               nil)
        [x y z] (if (= kind VfxOutputKind/AUDIO)
                  (audio-position->xyz (:position op))
                  [0.0 0.0 0.0])]
    (when kind
      (VfxOutput. kind (int (hash (or (:sound-id op) (:effect op) :none)))
                  (float (or (:volume op) (:value op) (:amplitude op) 0.0))
                  (some-> (or (:sound-id op) (:effect op)) str)
                  (some-> (:instance-key op) pr-str)
                  (boolean (:looping? op)) x y z))))

(defn ->java-frame
  "frame-id, resource-generation, sample-frame!'s output
   ({instance-key {:scene [op...] :emitters [...]}}) -> a VfxFrame.
   Scene ops become their normal draw/audio outputs. Each Niagara-style
   emitter becomes one `particle` batch whose payload carries the immutable
   layout plus the live ParticleColumns buffer; the neutral Java ABI remains
   unchanged while the renderer receives the complete SoA data it needs."
  ^VfxFrame [frame-id resource-generation sampled]
  (let [batches (ArrayList.) outputs (ArrayList.)]
    (doseq [[instance-key {:keys [scene]}] sampled
            raw-op scene]
      (when-let [legacy (some-> (legacy-op raw-op)
                                (assoc :instance-key instance-key))]
        (if (= :draw-batch (:operation legacy))
          (.add batches (op->java-batch legacy))
          (when-let [output (op->java-output legacy)] (.add outputs output)))))
    (doseq [[instance-key {:keys [emitters]}] sampled
            {:keys [layout buffer]} emitters]
      (let [^ParticleColumns particle-buffer buffer]
        (.add batches
              (VfxBatch. VfxRenderStage/WORLD_TRANSLUCENT
                         (int (hash (:id layout)))
                         "particle"
                         (int (.size particle-buffer))
                         nil
                         {:instance-key instance-key
                          :layout layout
                          :particles particle-buffer}))))
    (VfxFrame. (long frame-id) (long resource-generation) batches outputs)))

