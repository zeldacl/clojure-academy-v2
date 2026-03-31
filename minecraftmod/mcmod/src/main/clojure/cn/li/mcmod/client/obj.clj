(ns cn.li.mcmod.client.obj
  "Pure Clojure OBJ parser and legacy-style renderer.

  Replaces cn.lambdalib2.render.obj runtime dependency with data-first API."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [cn.li.mcmod.util.render :as render]
            [cn.li.mcmod.util.parse :as parse]
            [cn.li.mcmod.client.render.buffer :as buffer]
            [cn.li.mcmod.client.render.pose :as pose]))

(defn- v3 [x y z]
  {:x (double x) :y (double y) :z (double z)})

(defn- v2 [u v]
  {:u (double u) :v (double v)})

(defn- v3- [a b]
  (v3 (- (:x a) (:x b)) (- (:y a) (:y b)) (- (:z a) (:z b))))

(defn- v3-cross [a b]
  (v3 (- (* (:y a) (:z b)) (* (:z a) (:y b)))
      (- (* (:z a) (:x b)) (* (:x a) (:z b)))
      (- (* (:x a) (:y b)) (* (:y a) (:x b)))))

(defn- v3-norm [a]
  (let [len (Math/sqrt (+ (* (:x a) (:x a))
                          (* (:y a) (:y a))
                          (* (:z a) (:z a))))]
    (if (zero? len)
      (v3 0.0 0.0 0.0)
      (v3 (/ (:x a) len) (/ (:y a) len) (/ (:z a) len)))))

(defn- map-model-pos
  "Map OBJ model coordinates to Minecraft render coordinates.
  Current mapping applies axis swap + handedness fix discovered during
  migration verification against legacy visual output."
  [pos]
  {:x (double (:x pos))
   :y (double (:y pos))
   :z (double (:z pos))})

(defn- map-model-normal
  [nrm]
  {:x (double (:x nrm))
   :y (double (:y nrm))
   :z (double (:z nrm))})

(def ^:dynamic *skip-downward-faces*
  "When true, faces with downward-pointing normals are skipped.
  Useful for block-mounted models where the underside should not occlude
  the supporting block's top surface."
  false)

(def ^:dynamic *skip-model-bottom-faces*
  "When true, triangles on the model's lowest Y plane are skipped.
  This is more robust than normal-based culling for OBJ files with mixed
  winding or imperfect normals."
  false)

(def ^:dynamic *force-fullbright*
  "When true, OBJ buffered rendering uses fullbright light to avoid models
  turning black due to platform/light interop differences."
  true)

(def ^:dynamic *skip-flat-bottom-plane*
  "When true, remove triangles that are exactly on the current part's minimum
  Y plane (all 3 vertices near min Y). This is a precise way to avoid support
  block depth conflicts without broad face culling."
  false)

(def ^:dynamic *bottom-plane-epsilon*
  "Tolerance for flat-bottom-plane matching in model-space Y units."
  0.0005)

(defn- parse-face-vertex-token [token]
  (let [[v-str vt-str vn-str] (str/split token #"/")
        parse-int (fn [x] (when (and x (not= x "")) (Long/parseLong x)))]
    {:v (parse-int v-str)
     :vt (parse-int vt-str)
     :vn (parse-int vn-str)}))

(defn- resolve-obj-index [idx cnt]
  (cond
    (nil? idx) nil
    (pos? idx) (dec idx)
    (neg? idx) (+ cnt idx)
    :else nil))

(defn- tokenize-line [line]
  (->> (str/split (str/trim line) #"\s+")
       (remove str/blank?)))

(defn- read-obj-data [resource-path]
  (if-let [res (io/resource resource-path)]
    (slurp res)
    (throw (ex-info "OBJ resource not found" {:resource-path resource-path}))))

(defn parse-obj
  "Parse OBJ text into renderable model data.

  Supports: v, vt, vn, g/o, f (triangles and polygons via fan triangulation).
  Ignores: usemtl, mtllib, s and unknown tokens." 
  [obj-text]
  (let [state (atom {:positions []
                     :uvs []
                     :normals []
                     :raw-faces []
                     :current-group "Default"})]
    (doseq [line (str/split-lines (str obj-text))]
      (let [ln (str/trim line)]
        (when (and (not (str/blank? ln))
                   (not (str/starts-with? ln "#")))
          (let [[token & args] (tokenize-line ln)]
            (case token
              "v" (when (>= (count args) 3)
                    (swap! state update :positions conj
                           (v3 (parse/parse-float (nth args 0))
                               (parse/parse-float (nth args 1))
                               (parse/parse-float (nth args 2)))))
              "vt" (when (>= (count args) 2)
                     (swap! state update :uvs conj
                            (v2 (parse/parse-float (nth args 0))
                                (parse/parse-float (nth args 1)))))
              "vn" (when (>= (count args) 3)
                     (swap! state update :normals conj
                            (v3 (parse/parse-float (nth args 0))
                                (parse/parse-float (nth args 1))
                                (parse/parse-float (nth args 2)))))
              ("g" "o") (when (seq args)
                            (swap! state assoc :current-group (first args)))
              "f" (let [face-verts (mapv parse-face-vertex-token args)]
                    (when (>= (count face-verts) 3)
                      (swap! state update :raw-faces conj
                             {:group (:current-group @state)
                              :verts face-verts})))
              nil)))))
    (let [{:keys [positions uvs normals raw-faces]} @state
          generated (atom {})
          vertices (atom [])
          faces-by-group (atom {})
          add-vertex! (fn [vref]
                        (let [pid (resolve-obj-index (:v vref) (count positions))
                              tid (resolve-obj-index (:vt vref) (count uvs))
                  nid (resolve-obj-index (:vn vref) (count normals))
                  key [pid tid nid]]
                          (if-let [idx (@generated key)]
                            idx
                            (let [pos (or (nth positions pid nil) (v3 0.0 0.0 0.0))
                                  uv (or (when (some? tid) (nth uvs tid nil)) (v2 0.0 0.0))
                    nrm (or (when (some? nid) (nth normals nid nil)) (v3 0.0 1.0 0.0))
                                  idx (count @vertices)]
                              (swap! generated assoc key idx)
                              (swap! vertices conj {:pos pos
                                                    :uv uv
                            :normal nrm
                                                    :tangent (v3 0.0 0.0 0.0)})
                              idx))))
          add-face! (fn [group i0 i1 i2]
                      (let [v0 (nth @vertices i0)
                            v1 (nth @vertices i1)
                            v2 (nth @vertices i2)
                            edge1 (v3- (:pos v1) (:pos v0))
                            edge2 (v3- (:pos v2) (:pos v0))
                            duv1 {:u (- (:u (:uv v1)) (:u (:uv v0)))
                                  :v (- (:v (:uv v1)) (:v (:uv v0)))}
                            duv2 {:u (- (:u (:uv v2)) (:u (:uv v0)))
                                  :v (- (:v (:uv v2)) (:v (:uv v0)))}
                            det (- (* (:u duv1) (:v duv2))
                                   (* (:u duv2) (:v duv1)))
                            ff (if (zero? det) 0.0 (/ 1.0 det))
                            tangent (if (zero? det)
                                      (v3 0.0 0.0 0.0)
                                      (v3-norm
                                       (v3 (* ff (- (* (:v duv2) (:x edge1)) (* (:v duv1) (:x edge2))))
                                           (* ff (- (* (:v duv2) (:y edge1)) (* (:v duv1) (:y edge2))))
                                           (* ff (- (* (:v duv2) (:z edge1)) (* (:v duv1) (:z edge2)))))))
                            face-normal (v3-norm (v3-cross edge1 edge2))]
                        (swap! faces-by-group update group (fnil conj [])
                   {:i0 i0 :i1 i1 :i2 i2 :tangent tangent :normal face-normal})))]
      (doseq [{:keys [group verts]} raw-faces]
        (let [base (first verts)
              rest-verts (rest verts)]
          (doseq [i (range 1 (count rest-verts))]
            (let [tri [base (nth rest-verts (dec i)) (nth rest-verts i)]
                  [i0 i1 i2] (mapv add-vertex! tri)]
              (add-face! group i0 i1 i2)))))
      {:vertices (mapv (fn [v]
             (update v :tangent v3-norm))
               @vertices)
       :faces @faces-by-group})))

(defn load-obj-model
  "Load and parse OBJ model from assets namespace path, e.g. models/matrix.obj"
  [asset-path]
  (parse-obj (read-obj-data (str "assets/my_mod/" asset-path))))

(defn render-part!
  "Render one OBJ group/part from parsed model data." 
  [model part]
  (when-let [face-list (get (:faces model) part)]
    (render/gl-begin-triangles)
    (doseq [{:keys [i0 i1 i2]} face-list]
      (doseq [vertex-idx [i0 i1 i2]]
        (let [{:keys [pos uv normal]} (nth (:vertices model) vertex-idx)
              pos (map-model-pos pos)
              normal (map-model-normal normal)]
          (render/gl-normal (:x normal) (:y normal) (:z normal))
          (render/gl-tex-coord (:u uv) (- 1.0 (:v uv)))
          (render/gl-vertex (:x pos) (:y pos) (:z pos)))))
    (render/gl-end)))

(defn render-part-consumer
  "Render one OBJ group using a VertexConsumer. Uses the provided PoseStack
  for transforms and the provided vertex consumer for buffered submission." 
  [model part pose-stack vertex-consumer _packed-light packed-overlay]
  (when-let [face-list (get (:faces model) part)]
    (let [matrix (pose/get-matrix pose-stack)
      vc vertex-consumer
          part-vertex-indices (set (mapcat (fn [{:keys [i0 i1 i2]}]
                                             [i0 i1 i2])
                                           face-list))
          ;; Pre-calculate part-min-y once for all faces
          part-min-y (reduce min
                             (map (fn [idx]
                                    (:y (:pos (nth (:vertices model) idx))))
                                  part-vertex-indices))
          bottom-epsilon (double *bottom-plane-epsilon*)
          ;; Cache vertex lookups to avoid repeated nth calls
          vertices (:vertices model)]
      (doseq [{:keys [i0 i1 i2 normal]} face-list
        :let [face-normal (map-model-normal normal)
          v0 (nth vertices i0)
          v1 (nth vertices i1)
          v2 (nth vertices i2)
          y0 (:y (:pos v0))
          y1 (:y (:pos v1))
          y2 (:y (:pos v2))
            face-min-y (min y0 y1 y2)
          bottom-verts (count (filter true?
                        [(<= (Math/abs (double (- y0 part-min-y))) bottom-epsilon)
                         (<= (Math/abs (double (- y1 part-min-y))) bottom-epsilon)
                         (<= (Math/abs (double (- y2 part-min-y))) bottom-epsilon)]))
          skip-flat-bottom? (and *skip-flat-bottom-plane*
                                 (= bottom-verts 3))
          skip-by-normal? (and *skip-downward-faces*
                     (<= (:y face-normal) -0.2))
          skip-by-bottom? (and *skip-model-bottom-faces*
                       (or (>= bottom-verts 2)
                         (and (>= bottom-verts 1)
                            (<= (:y face-normal) 0.15)
                          (<= (Math/abs (double (- face-min-y part-min-y)))
                              (* 2.0 bottom-epsilon))))) ]
        :when (not (or skip-flat-bottom?
                       skip-by-normal?
                       skip-by-bottom?))]
        ;; Entity RenderTypes are quad-based in modern MC. Submit a degenerate
        ;; 4th vertex so each OBJ triangle maps to one valid quad primitive.
        (doseq [vertex-idx [i0 i1 i2 i2]]
            (let [{:keys [pos uv normal]} (nth vertices vertex-idx)
              pos (map-model-pos pos)
              normal (map-model-normal normal)
                x (float (:x pos))
                y (float (:y pos))
              z (float (:z pos))
                u (float (:u uv))
                v (float (- 1.0 (:v uv)))
                packed-light (int (if *force-fullbright*
                                    0x00F000F0
                                    _packed-light))
                nx (float (:x normal))
                ny (float (:y normal))
                nz (float (:z normal))]
            (buffer/submit-vertex vc matrix x y z
                                  1.0 1.0 1.0 1.0
                                  u v (int packed-overlay) packed-light
                                  nx ny nz)))))))

(defn render-all!
  "Render all groups in parsed OBJ model." 
  ([model]
   (doseq [part (keys (:faces model))]
     (render-part! model part)))
  ([model pose-stack vertex-consumer packed-light packed-overlay]
   (doseq [part (keys (:faces model))]
     (render-part-consumer model part pose-stack vertex-consumer packed-light packed-overlay))))

(defn has-part?
  "Whether the model contains a named part/group." 
  [model part]
  (contains? (:faces model) part))
