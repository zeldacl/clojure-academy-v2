(ns cn.li.ac.ability.client.effects.ray-composite-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cn.li.ac.ability.client.effects.ray-composite :as rc]
            [cn.li.ac.ability.client.effects.rv3 :as v])
  (:import [cn.li.mcmod.math V3]
           [java.io File]))

(defn- perpendicular-extents
  "Largest offset of any emitted vertex from the beam axis, along the two
  perpendiculars. A tube reaches the radius on BOTH; a flat strip on one."
  [ops ^V3 start ^V3 end]
  (let [dir (v/vnorm (v/v- end start))
        cand (if (< (Math/abs (.-y dir)) 0.9) v/unit-y v/unit-x)
        r (v/vnorm (v/vcross cand dir))
        u (v/vnorm (v/vcross dir r))]
    (reduce (fn [[mr mu] p]
              (let [d (v/v- p start)]
                [(max mr (Math/abs (v/vdot d r)))
                 (max mu (Math/abs (v/vdot d u)))]))
            [0.0 0.0]
            (mapcat (fn [op] (map #(get op %) [:p0 :p1 :p2 :p3])) ops))))

(deftest cylinders-are-untextured-test
  ;; RendererRayCylinder draws through ShaderNotex — notex.frag, pure vertex
  ;; colour, no texture at all. Only RendererRayGlow's three boards are
  ;; textured (ShaderSimple).
  ;;
  ;; This is not a detail. effects/arc.png, the shared beam sprite, is 79%
  ;; fully transparent and averages alpha 13/255; texturing the tubes with it
  ;; multiplied them down to a few percent opacity, so every ray in the game
  ;; lost its tubes and read as nothing but the flat glow boards.
  (let [start (v/v3 0.0 64.0 0.0)
        end (v/v3 0.0 64.0 15.0)
        ops (rc/tube-ops start end 0.22 rc/outer-head-fix
                         {:r 106 :g 242 :b 106 :a 50})]
    (is (seq ops))
    (is (every? #(= rc/solid-texture (:texture %)) ops)
        "the cylinders must not sample a sprite")
    (is (not (re-find #"arc\.png" (str rc/solid-texture))))))

(deftest cylinders-are-round-not-flat-test
  (let [start (v/v3 0.0 64.0 0.0)
        end (v/v3 0.0 64.0 15.0)
        radius 0.22
        ops (rc/tube-ops start end radius rc/outer-head-fix
                         {:r 255 :g 255 :b 255 :a 255})
        [er eu] (perpendicular-extents ops start end)]
    ;; DIV = 12 around, 4-segment nose at each end plus the body
    (is (= 108 (count ops)))
    (is (< (Math/abs (- er radius)) 1.0e-6))
    (is (< (Math/abs (- eu radius)) 1.0e-6))))

(defn- source-root
  "The directory the ac Clojure sources sit under on the classpath."
  ^File []
  (let [here (io/file (.toURI (io/resource "cn/li/ac/ability/client/effects/ray_composite.clj")))]
    ;; .../<root>/cn/li/ac/ability/client/effects/ray_composite.clj
    (nth (iterate #(.getParentFile ^File %) here) 8)))

(defn- ray-composite-call-sites
  []
  (->> (file-seq (source-root))
       (filter #(str/ends-with? (.getName ^File %) ".clj"))
       (keep (fn [^File f]
               (let [s (slurp f)]
                 (when (re-find #"ray-composite/tube-ops" s)
                   [(.getName f) s]))))))

(deftest composite-layers-are-emitted-in-upstream-order-test
  ;; RendererRayComposite appends glow, cylinderIn, cylinderOut, and
  ;; RendererList draws in append order. Every layer is translucent and writes
  ;; depth, so emitting them out of order silently deletes whichever layer
  ;; loses the depth test:
  ;;
  ;;   tubes before glow  -> the 1.5-wide flat board covers the 0.44 tube and
  ;;                         the ray reads as a sheet from every angle.
  ;;   outer before inner -> the alpha-50 shell stamps depth over the alpha-230
  ;;                         core nested inside it, and the beam goes patchily
  ;;                         solid/hollow as the viewing angle changes.
  ;;
  ;; Both have shipped as bugs. The order lives at the call sites, not in
  ;; ray-composite, so this checks the call sites.
  (let [sites (ray-composite-call-sites)]
    (is (<= 7 (count sites)) "expected to find the ray call sites on the classpath")
    (doseq [[name s] sites]
      (let [inner (str/index-of s "ray-composite/inner-head-fix")
            outer (str/index-of s "ray-composite/outer-head-fix")
            glow (str/index-of s "ray-composite/glow-ops")]
        (when (and inner outer)
          (is (< inner outer) (str name ": cylinderIn must be emitted before cylinderOut")))
        ;; Most sites concat the glow and the tubes inline, so source order is
        ;; emission order. meltdowner builds the two as separate plans and
        ;; concats them at the end, so there the concat is what to check.
        (if (str/includes? s "glow-plan")
          (is (str/includes? s "(concat glow-plan ray-plan)")
              (str name ": the glow plan must be concatenated before the ray plan"))
          (when (and glow inner)
            (is (< glow inner)
                (str name ": the glow boards must be emitted before the cylinders"))))))))

(deftest glow-boards-stay-textured-test
  ;; The glow is the one textured layer, and it keeps its three sprites.
  (let [tex (rc/glow-textures "mdray")
        ops (rc/glow-ops (v/v3 0.0 70.0 -8.0) (v/v3 0.0 64.0 0.0) (v/v3 0.0 64.0 15.0)
                         {:textures tex :width 1.5 :color {:r 255 :g 255 :b 255 :a 204}})]
    (is (= 3 (count ops)))
    (is (= [(:blend-in tex) (:tile tex) (:blend-out tex)]
           (mapv :texture ops)))))
