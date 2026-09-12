(ns cn.li.ability.compose
  "Pure composition boundary for Combat, VFX and Presentation.

   A content pack supplies combat outcomes and VFX descriptors. This namespace
   is the only place that combines those values into a frame/result envelope;
   it never calls Minecraft or owns process-global state.

   merge-draw-lists / merge-vfx-into-frame are the UI+VFX frame-assembly
   boundary (Presentation Runtime v3 refactor, Phase 5): ability-runtime is
   the only module that api's both vfx-core and presentation-core, so the
   VfxBatch/VfxOutput -> RenderCommand mapping belongs here, not in ac
   (which used to own it despite depending on neither module directly).
   Client-side VFX *sampling* stays in ac (it is stateful, version-frame-
   dependent client sampling, not a pure fold) - only the fold itself moved."
  (:import [cn.li.mcmod.runtime FramePacket RenderPass RenderStage
            RenderCommand$Batch RenderCommand$AudioContribution
            RenderCommand$CameraContribution RenderCommand$PostProcess]
           [cn.li.mcmod.runtime.ui UiDrawList]
           [cn.li.mcmod.runtime.vfx VfxBatch VfxFrame VfxOutput VfxOutputKind VfxRenderStage]))

;; ============================== UiDrawList merge ==============================

(defn- copy-clamped-index [^ints src ^ints dst src-i dst-i offset]
  (let [si (int src-i) di (int dst-i) off (int offset)
        v (aget src si)]
    (aset dst di (int (if (neg? v) -1 (+ v off))))))

(defn merge-draw-lists
  "Concatenates N UiDrawLists (one stage's worth, one per mount) into one,
   offsetting each list's resource/clip indices so they stay valid against
   the merged resource/clip-rect arrays. The overwhelmingly common case
   (0 or 1 mount on a stage - Minecraft shows one Screen and one HUD at a
   time) is zero-copy; only >1 simultaneous mount pays for the merge."
  ^UiDrawList [generation draw-lists]
  (let [draw-lists (vec (remove nil? draw-lists))]
    (cond
      (empty? draw-lists) nil
      (= 1 (count draw-lists)) (first draw-lists)
      :else
      (let [total-cmds (reduce + (map #(.count ^UiDrawList %) draw-lists))
            total-runs (reduce + (map #(.runCount ^UiDrawList %) draw-lists))
            total-clips (reduce + (map #(quot (alength (.clipRects ^UiDrawList %)) 4) draw-lists))
            total-res (reduce + (map #(alength (.resources ^UiDrawList %)) draw-lists))
            op (int-array total-cmds) geom (float-array (* 4 total-cmds))
            rgba (int-array total-cmds) uv (float-array (* 4 total-cmds))
            res (int-array total-cmds) clip (int-array total-cmds)
            scalar (float-array total-cmds) aux (object-array total-cmds)
            run-start (int-array total-runs) run-end (int-array total-runs)
            run-op (int-array total-runs) run-res (int-array total-runs) run-clip (int-array total-runs)
            clip-rects (float-array (* 4 total-clips))
            resources (object-array total-res)]
        (loop [dls draw-lists cmd-off (int 0) run-off (int 0) clip-off (int 0) res-off (int 0)]
          (if (empty? dls)
            (UiDrawList. (long generation) total-cmds op geom rgba uv res clip scalar aux
                        total-runs run-start run-end run-op run-res run-clip clip-rects resources)
            (let [^UiDrawList dl (first dls)
                  n (.count dl) rc (.runCount dl)
                  nc (quot (alength (.clipRects dl)) 4)
                  nr (alength (.resources dl))
                  ^ints dl-res (.res dl) ^ints dl-clip (.clip dl)
                  ^ints dl-run-start (.runStart dl) ^ints dl-run-end (.runEnd dl)
                  ^ints dl-run-op (.runOp dl) ^ints dl-run-res (.runRes dl) ^ints dl-run-clip (.runClip dl)]
              (System/arraycopy (.op dl) 0 op cmd-off n)
              (System/arraycopy (.geom dl) 0 geom (* 4 cmd-off) (* 4 n))
              (System/arraycopy (.rgba dl) 0 rgba cmd-off n)
              (System/arraycopy (.uv dl) 0 uv (* 4 cmd-off) (* 4 n))
              (System/arraycopy (.scalar dl) 0 scalar cmd-off n)
              (System/arraycopy (.aux dl) 0 aux cmd-off n)
              (dotimes [i n]
                (copy-clamped-index dl-res res i (+ cmd-off i) res-off)
                (copy-clamped-index dl-clip clip i (+ cmd-off i) clip-off))
              (dotimes [i rc]
                (aset run-start (+ run-off i) (+ cmd-off (aget dl-run-start i)))
                (aset run-end (+ run-off i) (+ cmd-off (aget dl-run-end i)))
                (aset run-op (+ run-off i) (aget dl-run-op i))
                (copy-clamped-index dl-run-res run-res i (+ run-off i) res-off)
                (copy-clamped-index dl-run-clip run-clip i (+ run-off i) clip-off))
              (System/arraycopy (.clipRects dl) 0 clip-rects (* 4 clip-off) (* 4 nc))
              (System/arraycopy (.resources dl) 0 resources res-off nr)
              (recur (rest dls) (+ cmd-off n) (+ run-off rc) (+ clip-off nc) (+ res-off nr)))))))))

;; ============================== VFX fold ==============================

(def ^:private vfx-stage->render-stage
  {VfxRenderStage/WORLD_TRANSLUCENT RenderStage/WORLD_BEFORE_TRANSLUCENT
   VfxRenderStage/WORLD_ADDITIVE RenderStage/WORLD_GLOW
   VfxRenderStage/WORLD_AFTER_TRANSLUCENT RenderStage/WORLD_AFTER_TRANSLUCENT
   VfxRenderStage/FIRST_PERSON RenderStage/FIRST_PERSON
   VfxRenderStage/SCREEN RenderStage/SCREEN})

(defn- vfx-command
  "(.primitive batch) directly, NOT (str (.primitiveId batch)): the batch
   already carries the stable primitive name every loader's renderer
   compares against (see cn.li.mcmod.runtime.vfx.VfxBatch's docstring) --
   the previous int-then-stringify encoding never matched that string set,
   so no VFX draw batch this fold produced ever reached a renderer."
  [^VfxBatch batch]
  (let [primitive (.primitive batch)]
    (when (or (nil? primitive) (zero? (count primitive)))
      (throw (ex-info "VfxBatch missing primitive name"
                      {:stage (.stage batch) :material-id (.materialId batch)})))
    (RenderCommand$Batch. (or (get vfx-stage->render-stage (.stage batch))
                              RenderStage/WORLD_AFTER_TRANSLUCENT)
                          primitive (str (.materialId batch)) "vfx"
                          0 (long (.instanceCount batch)) "stable" (.payload batch))))

(defn- vfx-output-command
  "Map a VfxOutput to a RenderCommand. Uses `condp =` (not `case`) so Java
   enum constants compare by value — `case` on enums has bitten this fold
   before by falling through to nil, which then NPE'd inside RenderPass's
   List.copyOf."
  [^VfxOutput output]
  (condp = (.kind output)
    VfxOutputKind/AUDIO (RenderCommand$AudioContribution.
                         (or (.resourceId output) "") (.amount output) 1.0
                         (.instanceKey output) (.looping output)
                         (.x output) (.y output) (.z output))
    VfxOutputKind/CAMERA (RenderCommand$CameraContribution. (.amount output) 0.0 0.0 0.0)
    VfxOutputKind/SCREEN (RenderCommand$PostProcess. (.value output) (.amount output))
    (throw (ex-info "unsupported VfxOutputKind"
                    {:kind (.kind output) :resource-id (.resourceId output)}))))

(def ^:private vfx-output->render-stage
  {VfxOutputKind/AUDIO RenderStage/AUDIO
   VfxOutputKind/CAMERA RenderStage/CAMERA
   VfxOutputKind/SCREEN RenderStage/POST_PROCESS})

(def ^:private render-stage-order
  [RenderStage/WORLD_AFTER_SKY
   RenderStage/WORLD_BEFORE_TRANSLUCENT
   RenderStage/WORLD_AFTER_TRANSLUCENT
   RenderStage/WORLD_ALWAYS_ON_TOP
   RenderStage/WORLD_GLOW
   RenderStage/FIRST_PERSON
   RenderStage/CAMERA
   RenderStage/HUD_UNDERLAY
   RenderStage/HUD
   RenderStage/HUD_OVERLAY
   RenderStage/SCREEN
   RenderStage/POST_PROCESS
   RenderStage/AUDIO])

(defn merge-vfx-into-frame
  "Pure: folds an already-sampled VfxFrame's batches/outputs into
   ui-packet's world/effect passes, bucketed by RenderStage. ui-packet's
   own uiByStage is carried through untouched. Sampling the VfxFrame is the
   caller's job (ac/effect-controller) - this function never re-samples,
   so calling it once per stage submission (hud/screen/world) costs a
   cheap re-bucketing of already-sampled data, not the sampling itself."
  ^FramePacket [^FramePacket ui-packet ^VfxFrame vfx]
  (if (nil? vfx)
    ui-packet
    (let [vfx-pairs (concat
                     (keep (fn [^VfxBatch batch]
                             (when-let [stage (get vfx-stage->render-stage (.stage batch))]
                               [stage (vfx-command batch)]))
                           (.batches vfx))
                     (keep (fn [^VfxOutput output]
                             (when-let [stage (get vfx-output->render-stage (.kind output))]
                               [stage (vfx-output-command output)]))
                           (.outputs vfx)))
          commands-by-stage (reduce (fn [acc [stage command]]
                                      (when (nil? command)
                                        (throw (ex-info "VFX fold produced a nil RenderCommand"
                                                        {:stage stage})))
                                      (if stage
                                        (update acc stage (fnil conj []) command)
                                        acc))
                                    {} vfx-pairs)
          passes (->> render-stage-order
                      (keep (fn [stage]
                              (when-let [commands (not-empty (get commands-by-stage stage))]
                                ;; vec (not seq): List.copyOf rejects null elements and
                                ;; PersistentVector is a reliable java.util.List.
                                (RenderPass. stage (vec commands)))))
                      vec)]
      (FramePacket. (.frameId ui-packet) (.uiByStage ui-packet) passes))))

(defn normalize-scope
  [scope]
  (merge {:server-epoch 0 :world-epoch 0 :catalog-generation 0}
         (select-keys (or scope {}) [:server-epoch :world-epoch
                                      :catalog-generation :player-id
                                      :max-render-commands-per-frame])))

(defn compose-catalog
  "Create the immutable cross-core bundle consumed by a content pack.
   Combat, VFX and Presentation are values at this boundary; no content-pack
   policy or host adapter is allowed to mutate or reinterpret them here."
  [content-id node-environment combat vfx]
  (when-not (keyword? content-id)
    (throw (ex-info "catalog content id must be a keyword"
                    {:content-id content-id})))
  (when-not (and (map? node-environment)
                 (map? (:descriptors node-environment)))
    (throw (ex-info "catalog requires a normalized node environment"
                    {:node-environment node-environment})))
  (when-not (map? combat)
    (throw (ex-info "catalog requires combat content" {})))
  (when-not (map? vfx)
    (throw (ex-info "catalog requires vfx content" {})))
  {:content-id content-id
   :node-environment node-environment
   :combat combat
   :vfx vfx})

(defn catalog-fingerprint-input
  "Return only deterministic catalog data for network identity checks.

   Node environments contain executable function values under :impl and
   :extra-ops. Those values are valid runtime data but must never participate
   in a cross-process hash because function object identities are JVM-local."
  [{:keys [content-id node-environment combat vfx] :as bundle}]
  (when-not (map? bundle)
    (throw (ex-info "catalog bundle must be a map" {:bundle bundle})))
  (when-not (and (map? node-environment)
                 (map? (:descriptors node-environment)))
    (throw (ex-info "catalog bundle has no normalized node environment" {})))
  (let [descriptors (into (sorted-map)
                          (map (fn [[id descriptor]]
                                 [id (dissoc descriptor :impl)]))
                          (:descriptors node-environment))
        extra-op-ids (vec (sort (keys (:extra-ops node-environment))))]
    {:content-id content-id
     :node-environment {:descriptors descriptors
                        :extra-op-ids extra-op-ids}
     :combat combat
     :vfx vfx}))

(defn route-intent
  "Resolve an intent's recipient without allowing one player to mutate another
   player's state. :self is owner-only; :tracking/:world are explicit server
   broadcasts and are still filtered by the host adapter."
  [scope intent]
  (let [owner (:owner intent)
        audience (:audience intent)]
    (case (:type audience)
      :owner {:kind :player :players [owner]}
      :nearby {:kind :tracking :center owner :radius (double (or (:radius audience) 96.0))}
      :world {:kind :world}
      {:kind :tracking :center owner :radius 96.0})))

(defn compose-result
  [scope combat-result vfx-intents]
  {:scope (normalize-scope scope)
   :owner (:owner combat-result)
   :result (dissoc combat-result :vfx-signals)
   :vfx-intents (vec (map #(assoc % :route (route-intent scope %)) vfx-intents))})

(defn reduce-player
  "Activation-local reducer. The mutable shell may store the returned value in
   a player shard; no server-global atom is required."
  [state event]
  (let [player (:player-id event)]
    (when-not (= player (:player-id state))
      (throw (ex-info "cross-player state mutation" {:state-player (:player-id state)
                                                      :event-player player})))
    (case (:op event)
      :activate (-> state (update :active-sessions (fnil conj #{}) (:session-id event))
                    (update :last-seq (fnil max -1) (long (:sequence event))))
      :complete (-> state (update :active-sessions disj (:session-id event))
                    (update :last-seq (fnil max -1) (long (:sequence event))))
      state)))
