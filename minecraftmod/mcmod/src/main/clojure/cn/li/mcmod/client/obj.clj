(ns cn.li.mcmod.client.obj
  "Pure Clojure OBJ parser and legacy-style renderer.

  Replaces cn.lambdalib2.render.obj runtime dependency with data-first API."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [cn.li.mcmod.util.render :as render]
            [cn.li.mcmod.util.parse :as parse]
            [cn.li.mcmod.client.render.buffer :as buffer]))

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

(def ^:dynamic *obj-buffer-normal-mode*
  "How buffered (VertexConsumer) OBJ shading picks normals.
  `:reconcile` (default): file `vn` when dot(vn, n_face) >= `*obj-vn-face-align-min-dot*`,
  else face normal (Wavefront: vn should agree with facet orientation or results are undefined).
  `:face`: flat face normal everywhere. `:vertex`: file `vn` only (may look wrong if vn disagrees)."
  :reconcile)

(def ^:dynamic *obj-vn-face-align-min-dot*
  "Cosine threshold for `:reconcile` mode; below this use geometric face normal."
  0.12)

(defn- v3-dot [a b]
  (+ (* (double (:x a)) (double (:x b)))
     (* (double (:y a)) (double (:y b)))
     (* (double (:z a)) (double (:z b)))))

(defn- pt2 [x y]
  {:x (double x) :y (double y)})

(defn- cross2d-sign [o a b]
  (- (* (- (:x a) (:x o)) (- (:y b) (:y o)))
     (* (- (:y a) (:y o)) (- (:x b) (:x o)))))

(defn- polygon-winding-ccw? [pts]
  (pos? ^double
        (reduce
         (fn [^double s i]
           (let [j (long (mod (unchecked-inc (long i)) (count pts)))
                 pi (nth pts (long i))
                 pj (nth pts j)]
             (+ s (- (* (:x pi) (:y pj))
                     (* (:x pj) (:y pi))))))
         0.0
         (range (count pts)))))

(defn- convex-corner-2d? [prev curr next ccw?]
  (let [c (cross2d-sign prev curr next)]
    (if ccw?
      (> c 1e-10)
      (< c -1e-10))))

(defn- point-in-triangle-2d? [p a b c ccw?]
  (let [s1 (cross2d-sign a b p)
        s2 (cross2d-sign b c p)
        s3 (cross2d-sign c a p)]
    (if ccw?
      (and (>= s1 -1e-10) (>= s2 -1e-10) (>= s3 -1e-10))
      (and (<= s1 1e-10) (<= s2 1e-10) (<= s3 1e-10)))))

(defn- find-ear-local-index [inds pts2d ccw?]
  (let [nv (long (count inds))]
    (loop [ei 0]
      (when (< ei nv)
        (let [pi (long (mod (dec ei) nv))
              ni (long (mod (inc ei) nv))
              i-prev (nth inds pi)
              i-curr (nth inds ei)
              i-next (nth inds ni)
              op (nth pts2d i-prev)
              oc (nth pts2d i-curr)
              on (nth pts2d i-next)]
          (if (and (convex-corner-2d? op oc on ccw?)
                   (not (some (fn [iv]
                                (when (and (not= iv i-prev)
                                           (not= iv i-curr)
                                           (not= iv i-next))
                                  (point-in-triangle-2d? (nth pts2d iv) op oc on ccw?)))
                              inds)))
            ei
            (recur (unchecked-inc ei))))))))

(defn- fan-triplet-indices [n]
  (for [j (range 1 (dec n))]
    [0 j (inc j)]))

(defn- try-ear-clip-indices [pts2d]
  (let [n (count pts2d)]
    (when (>= n 3)
      (let [ccw? (polygon-winding-ccw? pts2d)]
        (loop [inds (vec (range n))
               acc []]
          (cond
            (< (count inds) 3) nil
            (= (count inds) 3) (conj acc [(inds 0) (inds 1) (inds 2)])
            :else (if-let [ei (find-ear-local-index inds pts2d ccw?)]
                    (let [nv (count inds)
                          pi (mod (dec ei) nv)
                          ni (mod (inc ei) nv)
                          tri [(nth inds pi) (nth inds ei) (nth inds ni)]
                          inds' (vec (concat (subvec inds 0 ei) (subvec inds (inc ei))))]
                      (recur inds' (conj acc tri)))
                    nil)))))))

(defn- triangulate-corner-indices-2d [pts2d]
  (let [n (count pts2d)]
    (cond
      (< n 3) []
      (= n 3) [[0 1 2]]
      :else (or (try-ear-clip-indices pts2d)
                (vec (fan-triplet-indices n))))))

(defn- dominant-drop-axis [nrm]
  (let [ax (Math/abs (double (:x nrm)))
        ay (Math/abs (double (:y nrm)))
        az (Math/abs (double (:z nrm)))]
    (cond (and (>= ax ay) (>= ax az)) :x
          (and (>= ay ax) (>= ay az)) :y
          :else :z)))

(defn- v3->pt2-drop [p axis]
  (case axis
    :x (pt2 (:y p) (:z p))
    :y (pt2 (:x p) (:z p))
    :z (pt2 (:x p) (:y p))))

(defn- polygon-plane-normal-from-positions [pts3d]
  (some (fn [i]
          (when-let [p0 (nth pts3d i nil)]
            (let [p1 (nth pts3d (inc i) nil)
                  p2 (nth pts3d (+ i 2) nil)]
              (when (and p1 p2)
                (let [e1 (v3- p1 p0)
                      e2 (v3- p2 p0)
                      cr (v3-cross e1 e2)
                      mag (+ (* (:x cr) (:x cr)) (* (:y cr) (:y cr)) (* (:z cr) (:z cr)))]
                  (when (> mag 1e-20)
                    (v3-norm cr)))))))
        (range (max 0 (- (count pts3d) 2)))))

(defn- resolve-obj-index [idx cnt]
  (cond
    (nil? idx) nil
    (pos? idx) (dec idx)
    (neg? idx) (+ cnt idx)
    :else nil))

(defn- positions-from-vrefs [positions verts]
  (mapv (fn [vref]
          (when-let [pid (resolve-obj-index (:v vref) (count positions))]
            (nth positions pid nil)))
        verts))

(defn- corner-triplets-for-ngon [positions verts]
  (let [pts3d (positions-from-vrefs positions verts)
        n (count pts3d)]
    (cond
      (< n 3) []
      (not (every? some? pts3d)) (vec (fan-triplet-indices n))
      (= n 3) [[0 1 2]]
      :else (let [nrm (or (polygon-plane-normal-from-positions pts3d) (v3 0.0 1.0 0.0))
                  ax (dominant-drop-axis nrm)
                  pts2d (mapv #(v3->pt2-drop % ax) pts3d)]
              (triangulate-corner-indices-2d pts2d)))))

(defn- read-resource-slurp-optional [resource-path]
  (some-> (io/resource resource-path) slurp))

(defn- strip-quotes-path [^String s]
  (str/trim (str/replace s #"^[\"']|[\"']$" "")))

(defn- normalize-mtl-path-to-asset-relative [^String base-dir ^String rel-path]
  (let [clean (str/replace (strip-quotes-path rel-path) #"\\" "/")
        base (-> base-dir (str/replace #"\\" "/"))
        p (.. (java.nio.file.Paths/get base (into-array String []))
              (resolve clean)
              normalize)]
    (.toString p)))

(defn- preprocess-obj-text
  "Strip UTF-8 BOM and Wavefront `\\` line continuations (see OBJ spec: general rules)."
  [^String text]
  (-> (str text)
      (str/replace #"^\uFEFF" "")
      (str/replace #"\\\r?\n" "")))

(defn- obj-text->logical-lines [text]
  (->> (str/split-lines (preprocess-obj-text text))
       (map str/trim)
       (remove str/blank?)))

(defn- parse-long-safe [s]
  (when (and s (not (str/blank? (str s))))
    (try (Long/parseLong (str/trim (str s)))
         (catch Exception _ nil))))

(defn- parse-face-vertex-token
  "Parse one `f` corner reference per Wavefront OBJ: `i`, `i/j`, `i//k`, `i/j/k`.
  Extra `/` segments are truncated to the first three fields (v / vt / vn)."
  [token]
  (when-not (str/blank? token)
    (if-not (str/includes? token "/")
      {:v (parse-long-safe token) :vt nil :vn nil}
      (let [raw (str/split token #"/" -1)
            parts (vec (take 3 (concat raw (repeat ""))))
            v-str (nth parts 0 "")
            vt-str (nth parts 1 "")
            vn-str (nth parts 2 "")]
        {:v (parse-long-safe v-str)
         :vt (when-not (str/blank? vt-str) (parse-long-safe vt-str))
         :vn (when-not (str/blank? vn-str) (parse-long-safe vn-str))}))))

(defn- tokenize-line [line]
  (->> (str/split (str/trim line) #"\s+")
       (remove str/blank?)))

(defn- parse-mtl [mtl-text base-dir]
  (let [current! (atom nil)
        mats! (atom {})]
    (doseq [line (obj-text->logical-lines mtl-text)]
      (when (not (str/starts-with? line "#"))
        (let [[tok & args] (tokenize-line line)]
          (case tok
            "newmtl" (reset! current! (when (seq args) (str/join " " args)))
            ("map_Kd" "map_kd")
            (when-let [mat @current!]
              (when-let [raw (some #(when (re-find #"(?i)\\.(png|jpg|jpeg|tga|bmp)$" %) %)
                                   (reverse args))]
                (try
                  (swap! mats! assoc mat {:map-kd (normalize-mtl-path-to-asset-relative base-dir raw)})
                  (catch Exception _ nil))))
            nil))))
    @mats!))

(defn- merge-mtllibs [asset-parent mtllib-names]
  (reduce (fn [acc mtl]
            (let [path (str "assets/my_mod/" asset-parent mtl)
                  txt (read-resource-slurp-optional path)]
              (if txt (merge acc (parse-mtl txt asset-parent)) acc)))
          {}
          mtllib-names))

(defn- read-obj-data [resource-path]
  (if-let [res (io/resource resource-path)]
    (slurp res)
    (throw (ex-info "OBJ resource not found" {:resource-path resource-path}))))

(defn parse-obj
  "Parse OBJ text into renderable model data (Wavefront polygon + basic MTL).

  Supports: `v`, `vt` (u v), `vn`, `g` / `o`, `f` (`i`, `i/j`, `i//k`, `i/j/k`),
  `mtllib`, `usemtl` (per-face material name); UTF-8 BOM; `\\\\` line continuation.
  N-gons use 2D ear clipping after projection onto the dominant plane of the
  polygon normal; degenerate / non-simple cases fall back to triangle fan.

  Does not parse: Bezier/surface (`vp`, `cstype`, `deg`, `surf`, `curv`, …),
  `p`/`l`, `call`, full MTL (illum, bump maps, options on `map_Kd`, …).

  Optional second arg `opts`: `{:asset-parent \"models/\"}` — directory of the
  OBJ file under `assets/my_mod/` (used to resolve `mtllib` and `map_Kd`)."
  ([obj-text] (parse-obj obj-text nil))
  ([obj-text opts]
   (let [asset-parent (or (:asset-parent opts) "")
         state (atom {:positions []
                      :uvs []
                      :normals []
                      :raw-faces []
                      :current-group "Default"
                      :current-material nil
                      :mtllib []})]
     (doseq [line (obj-text->logical-lines obj-text)]
       (when (not (str/starts-with? line "#"))
         (let [[token & args] (tokenize-line line)]
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
             "mtllib" (when (seq args)
                        (swap! state update :mtllib into args))
             "usemtl" (when (seq args)
                        (swap! state assoc :current-material (str/join " " args)))
             "f" (let [face-verts (->> args
                                        (map parse-face-vertex-token)
                                        (remove nil?)
                                        vec)]
                   (when (>= (count face-verts) 3)
                     (swap! state update :raw-faces conj
                            {:group (:current-group @state)
                             :verts face-verts
                             :material (:current-material @state)})))
             nil))))
     (let [{:keys [positions uvs normals raw-faces mtllib]} @state
           materials (merge-mtllibs asset-parent (vec mtllib))
           generated (atom {})
           vertices (atom [])
           faces-by-group (atom {})
           add-vertex! (fn [vref]
                         (let [pid (resolve-obj-index (:v vref) (count positions))]
                           (when (some? pid)
                             (let [tid (resolve-obj-index (:vt vref) (count uvs))
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
                                   idx))))))
           add-face! (fn [group material i0 i1 i2]
                       (when (and (some? i0) (some? i1) (some? i2))
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
                               cross (v3-cross edge1 edge2)
                               face-normal (v3-norm cross)
                               face-base {:i0 i0 :i1 i1 :i2 i2 :tangent tangent :normal face-normal}]
                           (swap! faces-by-group update group (fnil conj [])
                                  (cond-> face-base material (assoc :material material))))))]
       (doseq [{:keys [group verts material]} raw-faces]
         (doseq [[ia ib ic] (corner-triplets-for-ngon positions verts)]
           (let [tri [(nth verts ia) (nth verts ib) (nth verts ic)]
                 [i0 i1 i2] (mapv add-vertex! tri)]
             (when (every? some? [i0 i1 i2])
               (add-face! group material i0 i1 i2)))))
       (cond-> {:vertices (mapv (fn [v]
                                 (update v :tangent v3-norm))
                               @vertices)
                :faces @faces-by-group}
         (seq materials) (assoc :materials materials))))))

(defn load-obj-model
  "Load and parse OBJ from `assets/my_mod/<asset-path>`. Resolves `mtllib` /
  `map_Kd` using the directory of `asset-path` (e.g. `models/foo.obj` →
  `models/`). Optional `opts` is merged into `parse-obj` (typically `{}`)."
  ([asset-path]
   (load-obj-model asset-path nil))
  ([asset-path opts]
   (let [slash (.lastIndexOf ^String asset-path (int \/))
         parent (if (pos? slash) (subs asset-path 0 (inc slash)) "")]
     (parse-obj (read-obj-data (str "assets/my_mod/" asset-path))
                (merge {:asset-parent parent} opts)))))

(def ^:private default-mtl-key ::default-mtl)

(defn- buffered-emit-part-faces!
  "Emit triangles for `face-list` using an existing VertexConsumer (single texture)."
  [vertices part-min-y bottom-epsilon face-list pose-stack vc packed-light packed-overlay]
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
                                                   (* 2.0 bottom-epsilon)))))]
          :when (not (or skip-flat-bottom?
                         skip-by-normal?
                         skip-by-bottom?))]
    (let [fnx (double (:x face-normal))
          fny (double (:y face-normal))
          fnz (double (:z face-normal))
          fn-len (Math/sqrt (+ (* fnx fnx) (* fny fny) (* fnz fnz)))
          face-unit (if (< fn-len 1.0e-8)
                      (v3 0.0 1.0 0.0)
                      (v3 (/ fnx fn-len) (/ fny fn-len) (/ fnz fn-len)))
          align-thresh (double *obj-vn-face-align-min-dot*)]
      (doseq [vertex-idx [i0 i1 i2 i2]]
        (let [{:keys [pos uv normal]} (nth vertices vertex-idx)
              pos (map-model-pos pos)
              vn-raw (map-model-normal normal)
              vn-unit (v3-norm vn-raw)
              n-pick (case *obj-buffer-normal-mode*
                       :face face-unit
                       :vertex (if (< (+ (* (:x vn-unit) (:x vn-unit))
                                         (* (:y vn-unit) (:y vn-unit))
                                         (* (:z vn-unit) (:z vn-unit)))
                                      1.0e-16)
                                  face-unit
                                  vn-unit)
                       (if (< (+ (* (:x vn-unit) (:x vn-unit))
                                (* (:y vn-unit) (:y vn-unit))
                                (* (:z vn-unit) (:z vn-unit)))
                              1.0e-16)
                         face-unit
                         (let [d (v3-dot vn-unit face-unit)]
                           (if (>= d align-thresh) vn-unit face-unit))))
              n-fin-raw (v3-norm n-pick)
              n-fin (if (< (+ (* (:x n-fin-raw) (:x n-fin-raw))
                              (* (:y n-fin-raw) (:y n-fin-raw))
                              (* (:z n-fin-raw) (:z n-fin-raw)))
                           1.0e-16)
                      (v3 0.0 1.0 0.0)
                      n-fin-raw)
              x (float (:x pos))
              y (float (:y pos))
              z (float (:z pos))
              u (float (:u uv))
              v (float (- 1.0 (:v uv)))
              packed-light (int (if *force-fullbright*
                                  0x00F000F0
                                  packed-light))
              nx (float (:x n-fin))
              ny (float (:y n-fin))
              nz (float (:z n-fin))]
          (buffer/submit-vertex vc pose-stack x y z
                                 1.0 1.0 1.0 1.0
                                 u v (int packed-overlay) packed-light
                                 nx ny nz))))))

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
  for transforms and the provided vertex consumer for buffered submission.
  Faces may carry optional `:material` from `usemtl`; single-texture callers
  ignore it. For per-material textures see `render-part-consumer-multi`."
  [model part pose-stack vertex-consumer packed-light packed-overlay]
  (when-let [face-list (get (:faces model) part)]
    (when (seq face-list)
      (let [part-vertex-indices (set (mapcat (fn [{:keys [i0 i1 i2]}]
                                               [i0 i1 i2])
                                             face-list))
            part-min-y (if (seq part-vertex-indices)
                         (reduce min
                                 (map (fn [idx]
                                        (:y (:pos (nth (:vertices model) idx))))
                                      part-vertex-indices))
                         0.0)
            bottom-epsilon (double *bottom-plane-epsilon*)
            vertices (:vertices model)]
        (buffered-emit-part-faces! vertices part-min-y bottom-epsilon face-list
                                   pose-stack vertex-consumer packed-light packed-overlay)))))

(defn render-part-consumer-multi
  "Buffered OBJ draw with a **fresh** VertexConsumer per material batch.
  `material->texture` maps MTL material name (string) to the same texture type
  your platform uses for `buffer/get-solid-buffer`. Unknown / default faces
  (no `:material`) use `default-texture`."
  [model part pose-stack buffer-source default-texture packed-light packed-overlay material->texture]
  (when-let [face-list (get (:faces model) part)]
    (when (seq face-list)
      (let [vertices (:vertices model)
            part-vertex-indices (set (mapcat (fn [{:keys [i0 i1 i2]}]
                                               [i0 i1 i2])
                                             face-list))
            part-min-y (if (seq part-vertex-indices)
                         (reduce min
                                 (map (fn [idx]
                                        (:y (:pos (nth (:vertices model) idx))))
                                      part-vertex-indices))
                         0.0)
            bottom-epsilon (double *bottom-plane-epsilon*)
            batches (->> face-list
                         (group-by #(or (:material %) default-mtl-key))
                         (sort-by (fn [[k _]] (if (= k default-mtl-key) "" (str k)))))]
        (doseq [[mat-key batch] batches]
          (let [tex (if (= mat-key default-mtl-key)
                      default-texture
                      (or (when material->texture (material->texture mat-key))
                          default-texture))
                vc (buffer/get-solid-buffer buffer-source tex)]
            (buffered-emit-part-faces! vertices part-min-y bottom-epsilon batch
                                       pose-stack vc packed-light packed-overlay)))))))

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
