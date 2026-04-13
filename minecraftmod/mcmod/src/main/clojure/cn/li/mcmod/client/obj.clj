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

(defn- v3+ [a b]
  (v3 (+ (:x a) (:x b)) (+ (:y a) (:y b)) (+ (:z a) (:z b))))

(defn- v3* [a s]
  (v3 (* (:x a) s) (* (:y a) s) (* (:z a) s)))

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

(def ^:dynamic *obj-max-lod*
  "Optional LOD ceiling for OBJ `lod` display attribute.
  nil means disabled (render all)."
  nil)

(defn- v3-dot [a b]
  (+ (* (double (:x a)) (double (:x b)))
     (* (double (:y a)) (double (:y b)))
     (* (double (:z a)) (double (:z b)))))

(def ^:dynamic *freeform-curve-ribbon-width*
  "Half-width for free-form curve ribbon tessellation in model units."
  0.003)

(def ^:dynamic *freeform-default-steps*
  "Default tessellation step count for free-form curves/surfaces."
  16)

(defn- clamp01 [t]
  (max 0.0 (min 1.0 (double t))))

(defn- lerp-v3 [a b t]
  (let [u (- 1.0 t)]
    (v3 (+ (* u (:x a)) (* t (:x b)))
        (+ (* u (:y a)) (* t (:y b)))
        (+ (* u (:z a)) (* t (:z b))))))

(defn- de-casteljau [points t]
  (loop [row (vec points)]
    (if (<= (count row) 1)
      (or (first row) (v3 0.0 0.0 0.0))
      (recur (mapv (fn [i] (lerp-v3 (nth row i) (nth row (inc i)) t))
                   (range (dec (count row))))))))

(defn- sample-bezier-curve [ctrl n-steps]
  (let [steps (max 1 (long n-steps))]
    (mapv (fn [i]
            (let [t (/ (double i) (double steps))]
              (de-casteljau ctrl (clamp01 t))))
          (range (inc steps)))))

(defn- sample-bezier-surface-grid [ctrl-grid u-steps v-steps]
  (let [us (max 1 (long u-steps))
  vs (max 1 (long v-steps))]
    (mapv (fn [vi]
            (let [t (/ (double vi) (double vs))
                  col-ctrl (mapv (fn [row] (de-casteljau row t)) ctrl-grid)]
              (sample-bezier-curve col-ctrl us)))
          (range (inc vs)))))

(defn- clamped-uniform-knots [n-ctrl degree]
  (let [p (max 1 (long degree))
        n (max 1 (long n-ctrl))
        m (+ n p 1)
        interior (- m (* 2 p) 1)]
    (vec
     (concat
      (repeat (inc p) 0.0)
      (when (pos? interior)
        (map (fn [i] (/ (double i) (double (inc interior))))
             (range 1 (inc interior))))
      (repeat (inc p) 1.0)))))

(defn- knot-vector-for [parm axis n-ctrl degree]
  (let [k (get parm axis)]
    (if (and (vector? k) (>= (count k) (+ n-ctrl degree 1)))
      (mapv double k)
      (clamped-uniform-knots n-ctrl degree))))

(defn- bspline-basis [knots i p t]
  (if (zero? p)
    (let [ki (double (nth knots i 0.0))
          kip1 (double (nth knots (inc i) 1.0))]
      (if (or (and (<= ki t) (< t kip1))
              (and (= t (double (last knots))) (= kip1 t)))
        1.0 0.0))
    (let [ki (double (nth knots i 0.0))
          kip (double (nth knots (+ i p) 0.0))
          ki1 (double (nth knots (inc i) 0.0))
          kip1 (double (nth knots (+ i p 1) 0.0))
          d1 (- kip ki)
          d2 (- kip1 ki1)
          t1 (if (zero? d1) 0.0 (* (/ (- t ki) d1) (bspline-basis knots i (dec p) t)))
          t2 (if (zero? d2) 0.0 (* (/ (- kip1 t) d2) (bspline-basis knots (inc i) (dec p) t)))]
      (+ t1 t2))))

(defn- eval-bspline-curve [ctrl knots degree t]
  (let [n (count ctrl)
        p (max 1 (long degree))
        tt (double t)]
    (loop [i 0
           acc (v3 0.0 0.0 0.0)]
      (if (>= i n)
        acc
        (let [b (bspline-basis knots i p tt)
              pi (nth ctrl i)]
          (recur (inc i)
                 (v3+ acc (v3* pi b))))))))

(defn- sample-bspline-curve [ctrl degree knots n-steps t0 t1]
  (let [steps (max 1 (long n-steps))
        a (double t0)
        b (double t1)]
    (mapv (fn [i]
            (let [tt (+ a (* (/ (double i) (double steps)) (- b a)))]
              (eval-bspline-curve ctrl knots degree tt)))
          (range (inc steps)))))

(defn- hp4 [x y z w]
  {:x (double x) :y (double y) :z (double z) :w (double w)})

(defn- hp4+ [a b]
  (hp4 (+ (:x a) (:x b)) (+ (:y a) (:y b)) (+ (:z a) (:z b)) (+ (:w a) (:w b))))

(defn- hp4* [a s]
  (hp4 (* (:x a) s) (* (:y a) s) (* (:z a) s) (* (:w a) s)))

(defn- pos-weight [p]
  (let [w (:w p)]
    (if (number? w) (double w) 1.0)))

(defn- pos->hp4 [p]
  (let [w (pos-weight p)]
    (hp4 (* (:x p) w) (* (:y p) w) (* (:z p) w) w)))

(defn- hp4->pos [h]
  (let [w (double (:w h))]
    (if (< (Math/abs w) 1.0e-12)
      (v3 (:x h) (:y h) (:z h))
      (v3 (/ (:x h) w) (/ (:y h) w) (/ (:z h) w)))))

(defn- lerp-hp4 [a b t]
  (let [u (- 1.0 t)]
    (hp4 (+ (* u (:x a)) (* t (:x b)))
         (+ (* u (:y a)) (* t (:y b)))
         (+ (* u (:z a)) (* t (:z b)))
         (+ (* u (:w a)) (* t (:w b))))))

(defn- de-casteljau-hp4 [points t]
  (loop [row (vec points)]
    (if (<= (count row) 1)
      (or (first row) (hp4 0.0 0.0 0.0 1.0))
      (recur (mapv (fn [i] (lerp-hp4 (nth row i) (nth row (inc i)) t))
                   (range (dec (count row))))))))

(defn- sample-rational-bezier-curve [ctrl n-steps]
  (let [hctrl (mapv pos->hp4 ctrl)
        steps (max 1 (long n-steps))]
    (mapv (fn [i]
            (let [t (/ (double i) (double steps))]
              (hp4->pos (de-casteljau-hp4 hctrl (clamp01 t)))))
          (range (inc steps)))))

(defn- sample-rational-bezier-surface-grid [ctrl-grid u-steps v-steps]
  (let [us (max 1 (long u-steps))
        vs (max 1 (long v-steps))
        hgrid (mapv (fn [row] (mapv pos->hp4 row)) ctrl-grid)]
    (mapv (fn [vj]
            (let [tv (/ (double vj) (double vs))
                  col (mapv (fn [row] (de-casteljau-hp4 row tv)) hgrid)]
              (mapv (fn [ui]
                      (let [tu (/ (double ui) (double us))]
                        (hp4->pos (de-casteljau-hp4 col tu))))
                    (range (inc us)))))
          (range (inc vs)))))

(defn- eval-bspline-curve-rational [ctrl knots degree t]
  (let [n (count ctrl)
        p (max 1 (long degree))
        tt (double t)]
    (loop [i 0
           num (v3 0.0 0.0 0.0)
           den 0.0]
      (if (>= i n)
        (if (< (Math/abs den) 1.0e-12)
          num
          (v3 (/ (:x num) den) (/ (:y num) den) (/ (:z num) den)))
        (let [b (bspline-basis knots i p tt)
              pi (nth ctrl i)
              wi (pos-weight pi)
              bw (* b wi)]
          (recur (inc i)
                 (v3+ num (v3* (v3 (:x pi) (:y pi) (:z pi)) bw))
                 (+ den bw)))))))

(defn- sample-bspline-curve-rational [ctrl degree knots n-steps t0 t1]
  (let [steps (max 1 (long n-steps))
        a (double t0)
        b (double t1)]
    (mapv (fn [i]
            (let [tt (+ a (* (/ (double i) (double steps)) (- b a)))]
              (eval-bspline-curve-rational ctrl knots degree tt)))
          (range (inc steps)))))

(defn- eval-bspline-surface-rational [ctrl-grid u-knots u-deg v-knots v-deg u v]
  (let [vc (count ctrl-grid)
        uc (if (pos? vc) (count (first ctrl-grid)) 0)
        uu (double u)
        vv (double v)]
    (let [[num den]
          (reduce (fn [[acc-num acc-den] j]
                    (let [bv (bspline-basis v-knots j v-deg vv)
                          row (nth ctrl-grid j)]
                      (reduce (fn [[n d] i]
                                (let [bu (bspline-basis u-knots i u-deg uu)
                                      p (nth row i)
                                      w (pos-weight p)
                                      b (* bu bv w)]
                                  [(v3+ n (v3* (v3 (:x p) (:y p) (:z p)) b))
                                   (+ d b)]))
                              [acc-num acc-den]
                              (range uc))))
                  [(v3 0.0 0.0 0.0) 0.0]
                  (range vc))]
      (if (< (Math/abs (double den)) 1.0e-12)
        num
        (v3 (/ (:x num) den) (/ (:y num) den) (/ (:z num) den))))))

(defn- sample-bspline-surface-grid-rational [ctrl-grid u-knots u-deg v-knots v-deg u-steps v-steps]
  (let [us (max 1 (long u-steps))
        vs (max 1 (long v-steps))
        u0 (double (first u-knots))
        u1 (double (last u-knots))
        v0 (double (first v-knots))
        v1 (double (last v-knots))]
    (mapv (fn [vj]
            (let [tv (+ v0 (* (/ (double vj) (double vs)) (- v1 v0)))]
              (mapv (fn [ui]
                      (let [tu (+ u0 (* (/ (double ui) (double us)) (- u1 u0)))]
                        (eval-bspline-surface-rational ctrl-grid u-knots u-deg v-knots v-deg tu tv)))
                    (range (inc us)))))
          (range (inc vs)))))

            (defn- infer-surface-control-dims [idx-count parm u-deg v-deg]
              (let [ku (get parm :u)
                 kv (get parm :v)
                 nu (when (and (vector? ku) (>= (count ku) (+ u-deg 2)))
                   (- (count ku) u-deg 1))
                 nv (when (and (vector? kv) (>= (count kv) (+ v-deg 2)))
                   (- (count kv) v-deg 1))
                 fits? (fn [u v]
                   (and (pos? u)
                     (pos? v)
                     (<= (* u v) idx-count)))
                 default-u (inc u-deg)
                 default-v (inc v-deg)]
                (cond
               (fits? nu nv) [nu nv]
               (and (pos? (or nu 0)) (zero? (mod idx-count nu))) [nu (quot idx-count nu)]
               (and (pos? (or nv 0)) (zero? (mod idx-count nv))) [(quot idx-count nv) nv]
               :else [default-u default-v])))

(defn- make-curve-ribbon-tris [pts half-width]
  (let [w (double half-width)
        up (v3 0.0 1.0 0.0)]
    (reduce (fn [acc i]
              (let [p0 (nth pts i)
                    p1 (nth pts (inc i))
                    tan (v3-norm (v3- p1 p0))
                    side0 (v3-cross tan up)
                    side (if (< (+ (* (:x side0) (:x side0))
                                   (* (:y side0) (:y side0))
                                   (* (:z side0) (:z side0)))
                                1.0e-10)
                           (v3 1.0 0.0 0.0)
                           (v3-norm side0))
                    off (v3* side w)
                    a (v3+ p0 off)
                    b (v3- p0 off)
                    c (v3+ p1 off)
                    d (v3- p1 off)]
                (conj acc [a b c] [c b d])))
            []
            (range (max 0 (dec (count pts)))))))

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
      (str/replace #"\\\\\r?\n" "")))

(defn- obj-text->logical-lines [text]
  (->> (str/split-lines (preprocess-obj-text text))
       (map str/trim)
       (remove str/blank?)))

(defn- parse-long-safe [s]
  (when (and s (not (str/blank? (str s))))
    (try (Long/parseLong (str/trim (str s)))
         (catch Exception _ nil))))

(defn- parse-smoothing-token [s]
  (let [v (some-> s str str/trim str/lower-case)]
    (cond
      (or (nil? v) (str/blank? v) (= v "off") (= v "0")) nil
      :else s)))

(defn- parse-merging-token [group-token resolution-token]
  (let [g (some-> group-token str str/trim)
        g-lc (some-> g str/lower-case)
        r (parse-long-safe resolution-token)]
    (cond
      (or (nil? g) (str/blank? g) (= g-lc "off") (= g "0")) nil
      :else {:group g :resolution r})))

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
       (remove str/blank?)
       ;; Wavefront comments start with '#'; allow trailing comments on data lines.
       (take-while #(not (str/starts-with? % "#")))))

(defn- split-quoted-or-bare-tokens [s]
  (let [m (re-seq #"\"[^\"]+\"|'[^']+'|[^\s]+" (str s))]
    (mapv strip-quotes-path m)))

(defn- parse-float-safe [s default]
  (let [x (parse/parse-float s default)]
    (if (number? x) (double x) (double default))))

(defn- parse-vec3-safe [args default]
  (let [d (or default [0.0 0.0 0.0])
        x (parse-float-safe (nth args 0 nil) (nth d 0))
        y (parse-float-safe (nth args 1 nil) (nth d 1))
        z (parse-float-safe (nth args 2 nil) (nth d 2))]
    [x y z]))

(defn- parse-map-statement [base-dir args]
  (let [opts-with-arity {"-blendu" 1 "-blendv" 1 "-boost" 1
                         "-mm" 2 "-o" 3 "-s" 3 "-t" 3
                         "-texres" 1 "-clamp" 1 "-bm" 1
                         "-imfchan" 1 "-type" 1}]
    (loop [rest-args (seq args)
           opts {}
           path-toks []]
      (if (empty? rest-args)
        (let [raw-path (str/join " " path-toks)
              path (when (seq path-toks)
                     (normalize-mtl-path-to-asset-relative base-dir raw-path))]
          (cond-> {:path path}
            (seq opts) (assoc :options opts)))
        (let [tok (first rest-args)]
          (if (str/starts-with? tok "-")
            (let [arity (long (get opts-with-arity tok 0))
                  vals (->> (rest rest-args)
                            (take arity)
                            vec)]
              (recur (drop (inc arity) rest-args)
                     (assoc opts tok (if (= 1 (count vals)) (first vals) vals))
                     path-toks))
            (recur (next rest-args) opts (conj path-toks tok))))))))

(defn- parse-mtl [mtl-text base-dir]
  (let [current! (atom nil)
        mats! (atom {})]
    (doseq [line (obj-text->logical-lines mtl-text)]
      (when (not (str/starts-with? line "#"))
        (let [[tok & args] (tokenize-line line)]
          (case tok
            "newmtl" (reset! current! (when (seq args) (str/join " " args)))
            ("Ka" "Kd" "Ks" "Ke" "Tf")
            (when-let [mat @current!]
              (swap! mats! update mat (fnil assoc {})
                (keyword (str/lower-case tok))
                (parse-vec3-safe args [0.0 0.0 0.0])))
            ("Ns" "Ni" "d" "Tr" "sharpness" "aniso" "anisor")
            (when-let [mat @current!]
              (swap! mats! update mat (fnil assoc {})
                (keyword (str/lower-case tok))
                (parse-float-safe (first args) 0.0)))
            "illum"
            (when-let [mat @current!]
              (swap! mats! update mat (fnil assoc {})
                :illum (long (or (parse-long-safe (first args)) 0))))
            ("map_Ka" "map_Kd" "map_Ks" "map_Ke" "map_d"
             "bump" "map_Bump" "disp" "decal" "refl")
            (when-let [mat @current!]
              (swap! mats! update mat (fnil assoc {})
                (keyword (str/lower-case tok))
                (parse-map-statement base-dir args)))
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

(defn- finalize-obj-state [state asset-parent]
  (let [{:keys [positions uvs normals param-vertices raw-faces raw-lines raw-points
                raw-free-curves raw-free-curves2 raw-free-surfaces raw-free-meta mtllib]} @state
        materials (merge-mtllibs asset-parent (vec mtllib))
        generated (atom {})
        vertices (atom [])
        faces-by-group (atom {})
        lines-by-group (atom {})
        points-by-group (atom {})
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
        add-generated-vertex! (fn [pos]
                                (let [key [::gen (:x pos) (:y pos) (:z pos) 0.0 0.0 0.0 1.0 0.0]]
                                  (if-let [idx (@generated key)]
                                    idx
                                    (let [idx (count @vertices)]
                                      (swap! generated assoc key idx)
                                      (swap! vertices conj {:pos pos
                                                            :uv (v2 0.0 0.0)
                                                            :normal (v3 0.0 1.0 0.0)
                                                            :tangent (v3 0.0 0.0 0.0)})
                                      idx))))
        resolve-pos-by-obj-idx (fn [idx]
                                 (when-let [rid (resolve-obj-index idx (count positions))]
                                   (nth positions rid nil)))
        resolve-vp-by-obj-idx (fn [idx]
                                (when-let [rid (resolve-obj-index idx (count param-vertices))]
                                  (nth param-vertices rid nil)))
        add-face! (fn [group display smoothing material i0 i1 i2]
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
                               (cond-> face-base
                                 (map? display) (assoc :display display)
                                 smoothing (assoc :smoothing smoothing)
                                 material (assoc :material material))))))
        add-line! (fn [group display smoothing material idxs]
                    (when (>= (count idxs) 2)
                      (let [line (cond-> {:idxs idxs}
                                   (map? display) (assoc :display display)
                                   smoothing (assoc :smoothing smoothing)
                                   material (assoc :material material))]
                        (swap! lines-by-group update group (fnil conj []) line))))
        add-point! (fn [group display smoothing material idx]
                     (when (some? idx)
                       (let [point (cond-> {:idx idx}
                                     (map? display) (assoc :display display)
                                     smoothing (assoc :smoothing smoothing)
                                     material (assoc :material material))]
                         (swap! points-by-group update group (fnil conj []) point))))]
    (doseq [{:keys [groups verts display smoothing merging material]} raw-faces]
      (doseq [[ia ib ic] (corner-triplets-for-ngon positions verts)]
        (let [tri [(nth verts ia) (nth verts ib) (nth verts ic)]
              [i0 i1 i2] (mapv add-vertex! tri)]
          (when (every? some? [i0 i1 i2])
            (doseq [group groups]
              (add-face! group display smoothing material i0 i1 i2)
              (when merging
                (swap! faces-by-group update group
                       (fn [items]
                         (if (seq items)
                           (let [last-idx (dec (count items))
                                 item (nth items last-idx)]
                             (assoc items last-idx (assoc item :merging merging)))
                           items)))))))))
    (doseq [{:keys [groups verts display smoothing merging material]} raw-lines]
      (let [idxs (->> verts (map add-vertex!) (filter some?) vec)]
        (when (>= (count idxs) 2)
          (doseq [group groups]
            (add-line! group display smoothing material idxs)
            (when merging
              (swap! lines-by-group update group
                     (fn [items]
                       (if (seq items)
                         (let [last-idx (dec (count items))
                               item (nth items last-idx)]
                           (assoc items last-idx (assoc item :merging merging)))
                         items))))))))
    (doseq [{:keys [groups verts display smoothing merging material]} raw-points]
      (doseq [vref verts]
        (when-let [idx (add-vertex! vref)]
          (doseq [group groups]
            (add-point! group display smoothing material idx)
            (when merging
              (swap! points-by-group update group
                     (fn [items]
                       (if (seq items)
                         (let [last-idx (dec (count items))
                               item (nth items last-idx)]
                           (assoc items last-idx (assoc item :merging merging)))
                         items))))))))

    ;; Free-form curves: Bezier is sampled; others fall back to control polyline.
    (doseq [{:keys [idxs cstype rat? deg step parm u0 u1 display groups smoothing material]} raw-free-curves]
      (let [ctrl (->> idxs (map resolve-pos-by-obj-idx) (remove nil?) vec)
            steps (max 2 (long (or (first step) *freeform-default-steps*)))
            sampled (cond
                      (and (= cstype "bezier") (>= (count ctrl) 2))
                      (if rat?
                        (sample-rational-bezier-curve ctrl steps)
                        (sample-bezier-curve ctrl steps))
                      (and (= cstype "bspline") (>= (count ctrl) 2))
                      (let [p (max 1 (long (or (first deg) 3)))
                            knots (knot-vector-for parm :u (count ctrl) p)
                            tmin (double (or u0 (first knots) 0.0))
                            tmax (double (or u1 (last knots) 1.0))]
                        (if rat?
                          (sample-bspline-curve-rational ctrl p knots steps tmin tmax)
                          (sample-bspline-curve ctrl p knots steps tmin tmax)))
                      :else ctrl)
            tris (make-curve-ribbon-tris sampled *freeform-curve-ribbon-width*)]
        (doseq [[a b c] tris]
          (let [i0 (add-generated-vertex! a)
                i1 (add-generated-vertex! b)
                i2 (add-generated-vertex! c)]
            (doseq [group groups]
              (add-face! group display smoothing material i0 i1 i2))))))

    ;; 2D free-form curves in vp-space: project to XY plane and render as ribbon.
    (doseq [{:keys [idxs cstype rat? deg step parm display groups smoothing material]} raw-free-curves2]
      (let [ctrl (->> idxs
                      (map resolve-vp-by-obj-idx)
                      (remove nil?)
                      (map (fn [{:keys [u v w]}]
                             (cond-> (v3 (double u) (double v) 0.0)
                               (number? w) (assoc :w (double w)))))
                      vec)
            steps (max 2 (long (or (first step) *freeform-default-steps*)))
            sampled (cond
                      (and (= cstype "bezier") (>= (count ctrl) 2))
                      (if rat?
                        (sample-rational-bezier-curve ctrl steps)
                        (sample-bezier-curve ctrl steps))
                      (and (= cstype "bspline") (>= (count ctrl) 2))
                      (let [p (max 1 (long (or (first deg) 3)))
                            knots (knot-vector-for parm :u (count ctrl) p)]
                        (if rat?
                          (sample-bspline-curve-rational ctrl p knots steps (first knots) (last knots))
                          (sample-bspline-curve ctrl p knots steps (first knots) (last knots))))
                      :else ctrl)
            tris (make-curve-ribbon-tris sampled *freeform-curve-ribbon-width*)]
        (doseq [[a b c] tris]
          (let [i0 (add-generated-vertex! a)
                i1 (add-generated-vertex! b)
                i2 (add-generated-vertex! c)]
            (doseq [group groups]
              (add-face! group display smoothing material i0 i1 i2))))))

    ;; Free-form surfaces: Bezier grid sampling; others use control-net fan fallback.
    (doseq [{:keys [idxs cstype rat? deg step parm display groups smoothing material]} raw-free-surfaces]
      (let [u-deg (max 1 (long (or (first deg) 3)))
            v-deg (max 1 (long (or (second deg) 3)))
            ctrl-all (->> idxs (map resolve-pos-by-obj-idx) (remove nil?) vec)
            [u-ctrl v-ctrl] (infer-surface-control-dims (count ctrl-all) parm u-deg v-deg)
            ctrl-grid (if (>= (count ctrl-all) (* u-ctrl v-ctrl))
                        (->> ctrl-all (take (* u-ctrl v-ctrl)) (partition u-ctrl) (mapv vec))
                        [])
            u-steps (max 2 (long (or (first step) *freeform-default-steps*)))
            v-steps (max 2 (long (or (second step) *freeform-default-steps*)))
            sampled-grid (cond
                           (and (= cstype "bezier") (seq ctrl-grid))
                           (if rat?
                             (sample-rational-bezier-surface-grid ctrl-grid u-steps v-steps)
                             (sample-bezier-surface-grid ctrl-grid u-steps v-steps))
                           (and (= cstype "bspline") (seq ctrl-grid))
                           (let [u-knots (knot-vector-for parm :u u-ctrl u-deg)
                                 v-knots (knot-vector-for parm :v v-ctrl v-deg)]
                             (if rat?
                               (sample-bspline-surface-grid-rational ctrl-grid u-knots u-deg v-knots v-deg u-steps v-steps)
                               (let [us (range (inc u-steps))
                                     vs (range (inc v-steps))]
                                 (mapv (fn [vj]
                                         (let [tv (+ (first v-knots)
                                                     (* (/ (double vj) (double (max 1 v-steps)))
                                                        (- (double (last v-knots)) (double (first v-knots)))))]
                                           (mapv (fn [ui]
                                                   (let [tu (+ (first u-knots)
                                                               (* (/ (double ui) (double (max 1 u-steps)))
                                                                  (- (double (last u-knots)) (double (first u-knots)))))]
                                                     (loop [iv 0
                                                            acc (v3 0.0 0.0 0.0)]
                                                       (if (>= iv v-ctrl)
                                                         acc
                                                         (let [bv (bspline-basis v-knots iv v-deg tv)
                                                               row (nth ctrl-grid iv)
                                                               cu (eval-bspline-curve row u-knots u-deg tu)]
                                                           (recur (inc iv) (v3+ acc (v3* cu bv))))))))
                                                 us)))
                                       vs))))
                           :else nil)]
        (if (seq sampled-grid)
          (doseq [vj (range (dec (count sampled-grid)))
                  ui (range (dec (count (nth sampled-grid vj))))]
            (let [a (nth (nth sampled-grid vj) ui)
                  b (nth (nth sampled-grid vj) (inc ui))
                  c (nth (nth sampled-grid (inc vj)) ui)
                  d (nth (nth sampled-grid (inc vj)) (inc ui))
                  ia (add-generated-vertex! a)
                  ib (add-generated-vertex! b)
                  ic (add-generated-vertex! c)
                  id (add-generated-vertex! d)]
              (doseq [group groups]
                (add-face! group display smoothing material ia ib ic)
                (add-face! group display smoothing material ib id ic))))
          (when (>= (count ctrl-all) 3)
            (let [idxs2 (mapv add-generated-vertex! ctrl-all)]
              (doseq [[ia ib ic] (fan-triplet-indices (count idxs2))]
                (doseq [group groups]
                  (add-face! group display smoothing material
                             (nth idxs2 ia) (nth idxs2 ib) (nth idxs2 ic)))))))))

    (cond-> {:vertices (mapv (fn [v]
                               (update v :tangent v3-norm))
                             @vertices)
             :faces @faces-by-group}
      (seq @lines-by-group) (assoc :lines @lines-by-group)
      (seq @points-by-group) (assoc :points @points-by-group)
      (seq param-vertices) (assoc :param-vertices (vec param-vertices))
      (or (seq raw-free-curves)
          (seq raw-free-curves2)
          (seq raw-free-surfaces)
          (seq raw-free-meta))
      (assoc :freeform {:curves raw-free-curves
                        :curves2 raw-free-curves2
                        :surfaces raw-free-surfaces
                        :meta raw-free-meta})
      (seq materials) (assoc :materials materials))))

(defn parse-obj
  "Parse OBJ text into renderable model data (Wavefront polygon + basic MTL).

  Supports: `v`, `vt` (`u [v] [w]`, keeps `u`/`v`), `vn`, `vp`, `g` / `o`, `s`, `mg`,
  display attrs (`bevel`, `c_interp`, `d_interp`, `lod`, `shadow_obj`, `trace_obj`, `ctech`, `stech`),
  free-form attrs/body (`cstype`, `deg`, `bmat`, `step`, `parm`, `curv`, `curv2`, `surf`,
  `trim`, `hole`, `scrv`, `sp`, `end`, `con`),
  `f` (`i`, `i/j`, `i//k`, `i/j/k`),
  `l`, `p`, `mtllib`, `usemtl` (per-face material name);
  UTF-8 BOM; `\\` line continuation.
  N-gons use 2D ear clipping after projection onto the dominant plane of the
  polygon normal; degenerate / non-simple cases fall back to triangle fan.

  `call` is not implemented.

  Optional second arg `opts`: `{:asset-parent \"models/\"}` — directory of the
  OBJ file under `assets/my_mod/` (used to resolve `mtllib` and `map_Kd`)."
  ([obj-text] (parse-obj obj-text nil))
  ([obj-text opts]
   (let [asset-parent (or (:asset-parent opts) "")
         state (atom {:positions []
                      :uvs []
                      :normals []
                      :param-vertices []
                      :raw-faces []
                      :raw-lines []
                      :raw-points []
                      :raw-free-curves []
                      :raw-free-curves2 []
                      :raw-free-surfaces []
                      :raw-free-meta []
                      :current-groups ["Default"]
                      :current-object nil
                      :current-smoothing nil
                      :current-merging nil
                      :current-cstype nil
                      :current-cstype-rat? false
                      :current-deg [3 3]
                      :current-step [(long *freeform-default-steps*) (long *freeform-default-steps*)]
                      :current-parm {}
                      :current-display {}
                      :current-material nil
                      :mtllib []})]
     (doseq [line (obj-text->logical-lines obj-text)]
       (when (not (str/starts-with? line "#"))
         (let [[token & args] (tokenize-line line)]
           (case token
             "v" (when (>= (count args) 3)
                   (swap! state update :positions conj
                          (cond-> (v3 (parse/parse-float (nth args 0))
                                      (parse/parse-float (nth args 1))
                                      (parse/parse-float (nth args 2)))
                            (>= (count args) 4)
                            (assoc :w (parse/parse-float (nth args 3) 1.0)))))
             "vt" (when (seq args)
                    (swap! state update :uvs conj
                           (v2 (parse/parse-float (nth args 0))
                               (parse/parse-float (nth args 1) 0.0))))
             "vn" (when (>= (count args) 3)
                    (swap! state update :normals conj
                           (v3 (parse/parse-float (nth args 0))
                               (parse/parse-float (nth args 1))
                               (parse/parse-float (nth args 2)))))
                   "vp" (when (seq args)
                    (swap! state update :param-vertices conj
                      {:u (parse/parse-float (nth args 0))
                  :v (parse/parse-float (nth args 1) 0.0)
                  :w (parse/parse-float (nth args 2) 1.0)}))
             "g" (swap! state assoc :current-groups (if (seq args) (vec args) ["Default"]))
             "o" (swap! state assoc :current-object (when (seq args) (str/join " " args)))
                   "s" (swap! state assoc :current-smoothing (parse-smoothing-token (first args)))
                   "mg" (swap! state assoc :current-merging (parse-merging-token (first args) (second args)))
                  "bevel" (swap! state assoc-in [:current-display :bevel] (first args))
                  "c_interp" (swap! state assoc-in [:current-display :c_interp] (first args))
                  "d_interp" (swap! state assoc-in [:current-display :d_interp] (first args))
                  "lod" (swap! state assoc-in [:current-display :lod] (parse-float-safe (first args) 0.0))
                  "shadow_obj" (swap! state assoc-in [:current-display :shadow_obj] (str/join " " args))
                  "trace_obj" (swap! state assoc-in [:current-display :trace_obj] (str/join " " args))
                  "ctech" (swap! state assoc-in [:current-display :ctech] (str/join " " args))
                  "stech" (swap! state assoc-in [:current-display :stech] (str/join " " args))
                  "cstype" (let [rat? (= "rat" (some-> (first args) str/lower-case))
                   kind (if rat? (second args) (first args))]
                    (swap! state assoc
                      :current-cstype (some-> kind str/lower-case)
                      :current-cstype-rat? rat?))
                  "deg" (swap! state assoc :current-deg
                    [(long (or (parse-long-safe (first args)) 3))
                     (long (or (parse-long-safe (second args))
                     (or (parse-long-safe (first args)) 3)))])
                  "step" (swap! state assoc :current-step
                     [(long (or (parse-long-safe (first args)) (long *freeform-default-steps*)))
                      (long (or (parse-long-safe (second args))
                      (or (parse-long-safe (first args)) (long *freeform-default-steps*))))])
                  "bmat" (swap! state assoc-in [:current-display :bmat] (mapv #(parse-float-safe % 0.0) args))
                  "parm" (when (>= (count args) 2)
                 (let [axis (keyword (str/lower-case (first args)))
                  vals (mapv #(parse-float-safe % 0.0) (rest args))]
                   (swap! state assoc-in [:current-parm axis] vals)))
                  "curv" (when (>= (count args) 3)
                 (let [u0 (parse-float-safe (nth args 0) 0.0)
                  u1 (parse-float-safe (nth args 1) 1.0)
                  idxs (->> (drop 2 args) (map parse-long-safe) (remove nil?) vec)]
                   (swap! state update :raw-free-curves conj
                     {:u0 u0 :u1 u1 :idxs idxs
                      :cstype (:current-cstype @state)
                      :rat? (:current-cstype-rat? @state)
                      :deg (:current-deg @state)
                      :step (:current-step @state)
                      :parm (:current-parm @state)
                      :display (:current-display @state)
                      :groups (:current-groups @state)
                      :smoothing (:current-smoothing @state)
                      :merging (:current-merging @state)
                      :material (:current-material @state)})))
                  "curv2" (when (seq args)
                  (let [idxs (->> args (map parse-long-safe) (remove nil?) vec)]
                    (swap! state update :raw-free-curves2 conj
                      {:idxs idxs
                       :cstype (:current-cstype @state)
                       :rat? (:current-cstype-rat? @state)
                       :deg (:current-deg @state)
                       :step (:current-step @state)
                       :parm (:current-parm @state)
                       :display (:current-display @state)
                       :groups (:current-groups @state)
                       :smoothing (:current-smoothing @state)
                       :merging (:current-merging @state)
                       :material (:current-material @state)})))
                  "surf" (when (>= (count args) 5)
                 (let [s0 (parse-float-safe (nth args 0) 0.0)
                  s1 (parse-float-safe (nth args 1) 1.0)
                  t0 (parse-float-safe (nth args 2) 0.0)
                  t1 (parse-float-safe (nth args 3) 1.0)
                  idxs (->> (drop 4 args) (map parse-long-safe) (remove nil?) vec)]
                   (swap! state update :raw-free-surfaces conj
                     {:s0 s0 :s1 s1 :t0 t0 :t1 t1 :idxs idxs
                      :cstype (:current-cstype @state)
                      :rat? (:current-cstype-rat? @state)
                      :deg (:current-deg @state)
                      :step (:current-step @state)
                      :parm (:current-parm @state)
                      :display (:current-display @state)
                      :groups (:current-groups @state)
                      :smoothing (:current-smoothing @state)
                      :merging (:current-merging @state)
                      :material (:current-material @state)})))
                  ("trim" "hole" "scrv" "sp" "con")
                  (swap! state update :raw-free-meta conj
                    {:kind (keyword (str/lower-case token))
                :args (vec args)
                :cstype (:current-cstype @state)
                :rat? (:current-cstype-rat? @state)
                :deg (:current-deg @state)
                :step (:current-step @state)
                :parm (:current-parm @state)
                :display (:current-display @state)
                :groups (:current-groups @state)
                :material (:current-material @state)})
                  "end" (swap! state assoc :current-parm {})
             "mtllib" (when (seq args)
                        (swap! state update :mtllib into (split-quoted-or-bare-tokens (str/join " " args))))
             "usemtl" (when (seq args)
                        (swap! state assoc :current-material (str/join " " args)))
             "f" (let [face-verts (->> args
                                        (map parse-face-vertex-token)
                                        (remove nil?)
                                        vec)]
                   (when (>= (count face-verts) 3)
                     (let [groups (let [gs (:current-groups @state)
                                        obj-name (:current-object @state)]
                                    (cond
                                      (seq gs) gs
                                      (some? obj-name) [obj-name]
                                      :else ["Default"]))]
                       (swap! state update :raw-faces conj
                              {:groups groups
                               :verts face-verts
                               :display (:current-display @state)
                               :smoothing (:current-smoothing @state)
                               :merging (:current-merging @state)
                               :material (:current-material @state)}))))
             "l" (let [line-verts (->> args
                                        (map parse-face-vertex-token)
                                        (remove nil?)
                                        vec)]
                   (when (>= (count line-verts) 2)
                     (let [groups (let [gs (:current-groups @state)
                                        obj-name (:current-object @state)]
                                    (cond
                                      (seq gs) gs
                                      (some? obj-name) [obj-name]
                                      :else ["Default"]))]
                       (swap! state update :raw-lines conj
                              {:groups groups
                               :verts line-verts
                               :display (:current-display @state)
                               :smoothing (:current-smoothing @state)
                               :merging (:current-merging @state)
                               :material (:current-material @state)}))))
             "p" (let [point-verts (->> args
                                         (map parse-face-vertex-token)
                                         (remove nil?)
                                         vec)]
                   (when (seq point-verts)
                     (let [groups (let [gs (:current-groups @state)
                                        obj-name (:current-object @state)]
                                    (cond
                                      (seq gs) gs
                                      (some? obj-name) [obj-name]
                                      :else ["Default"]))]
                       (swap! state update :raw-points conj
                              {:groups groups
                               :verts point-verts
                               :display (:current-display @state)
                               :smoothing (:current-smoothing @state)
                               :merging (:current-merging @state)
                               :material (:current-material @state)}))))
             nil))))
         (finalize-obj-state state asset-parent))))

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

(defn- material-rgba
  [materials mat-name]
  (let [m (when (and materials mat-name) (get materials mat-name))
        kd (or (:kd m) [1.0 1.0 1.0])
  ke (or (:ke m) [0.0 0.0 0.0])
        tr (:tr m)
        d (:d m)
        alpha (cond
                (number? d) (double d)
                (number? tr) (- 1.0 (double tr))
    :else 1.0)
  r (min 1.0 (+ (double (nth kd 0 1.0)) (double (nth ke 0 0.0))))
  g (min 1.0 (+ (double (nth kd 1 1.0)) (double (nth ke 1 0.0))))
  b (min 1.0 (+ (double (nth kd 2 1.0)) (double (nth ke 2 0.0))))]
    [(float r)
     (float g)
     (float b)
     (float (max 0.0 (min 1.0 alpha)))]))

(defn- buffered-emit-part-faces!
  "Emit triangles for `face-list` using an existing VertexConsumer (single texture)."
  [vertices materials part-min-y bottom-epsilon face-list pose-stack vc packed-light packed-overlay]
  (doseq [{:keys [i0 i1 i2 normal material display]} face-list
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
                         skip-by-bottom?
                         (let [lod (when (map? display) (:lod display))]
                           (and (number? lod)
                                (number? *obj-max-lod*)
                                (> (double lod) (double *obj-max-lod*))))))]
    (let [[cr cg cb ca] (material-rgba materials material)
          fnx (double (:x face-normal))
          fny (double (:y face-normal))
          fnz (double (:z face-normal))
          fn-len (Math/sqrt (+ (* fnx fnx) (* fny fny) (* fnz fnz)))
          face-unit (if (< fn-len 1.0e-8)
                      (v3 0.0 1.0 0.0)
                      (v3 (/ fnx fn-len) (/ fny fn-len) (/ fnz fn-len)))
          align-thresh (double *obj-vn-face-align-min-dot*)]
      ;; OBJ semantics are triangles; backend can expand the submission order
      ;; via `buffer/*triangle-vertex-order*` when needed.
      (let [tri [i0 i1 i2]]
        (doseq [ord (or buffer/*triangle-vertex-order* [0 1 2])
            :let [vertex-idx (nth tri (int ord))]]
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
              packed-light (int (if *force-fullbright* 0x00F000F0 packed-light))
              nx (float (:x n-fin))
              ny (float (:y n-fin))
              nz (float (:z n-fin))]
              (buffer/submit-vertex vc pose-stack x y z
                          cr cg cb ca
                          u v (int packed-overlay) packed-light
                          nx ny nz)))))))

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
          (buffered-emit-part-faces! vertices (:materials model) part-min-y bottom-epsilon face-list
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
              (buffered-emit-part-faces! vertices (:materials model) part-min-y bottom-epsilon batch
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
