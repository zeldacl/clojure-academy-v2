(ns cn.li.ac.vfx.particle-payload-contract-test
  "An emitter's :particle map has a contract, and most of it is not honoured.

   :emitter's :particle parameter is declared (opt :any nil) -- a free-form
   map with no schema anywhere -- so an effect may write any key into it and
   the compiler cannot object. The neutral render plan reads exactly six:
   :age :frame-count :frame-duration-ms :scale :size :texture. Everything
   else content writes is dropped between the scene op and the renderer.

   That is not a small gap. teleport-marker's particle carries main's tp_mark
   spec exactly -- alpha 153-204, fade-in 5 / fade-out 20, size 0.1-0.2,
   velocity ±0.03/0..0.05/±0.03, additive material, a green tint -- and the
   renderer applies the texture and the size. The particles are the wrong
   colour, never fade, and do not move.

   This is also the reason the last two incomplete conversions cannot be
   finished by authoring alone: blood-retrograde's splash needs per-splash
   colour and life, and block-scan's readout needs a colour per tool tier.
   Both are :particle keys the render plan does not read, so writing them
   would produce content that compiles and renders wrong -- the exact defect
   this file exists to stop.

   The check therefore pins the contract rather than the content: any key an
   effect writes must be one the renderer reads, or be listed below as a
   known-dropped key. Implementing one means deleting it from that list."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(def ^:private render-plan-reads
  "Read off vfx-render-plan's particle call sites -- (:age particle),
   (:size particle) and so on -- not inferred from the names. Six keys."
  #{:age :frame-count :frame-duration-ms :scale :size :texture})

(def ^:private known-dropped
  "Keys effects write that reach no renderer. Each is a visual the pre-V4
   implementation had: alpha/colour/material are its tint and blending,
   the fade pair is its alpha envelope, velocity/speed/spread its motion,
   and life-ticks its lifetime. Shrinking this list means implementing the
   key in vfx-render-plan, not deleting it from content -- the values are
   the originals' own numbers."
  #{:alpha :color :fade-in-ticks :fade-out-ticks :life-ticks :material
    :particle-type :speed :spread :velocity})

(defn- particle-maps
  "[[effect-id {particle-key value}] ...] for every :particle map written by
   a shipped effect."
  []
  (let [root (io/file (io/resource "ac/vfx-v4"))]
    (when-not root (throw (ex-info "ac/vfx-v4 not on the classpath" {})))
    (for [^java.io.File f (.listFiles root)
          :when (.endsWith (.getName f) ".edn")
          :let [doc (edn/read-string (slurp f))]
          form (tree-seq coll? seq (:phases doc))
          :when (and (map? form) (map? (:particle form)))]
      [(:id doc) (:particle form)])))

(deftest particle-payload-keys-are-read-or-known-dropped-test
  (is (seq (particle-maps))
      "no :particle maps found -- the check would pass vacuously")
  (let [offenders
        (for [[effect-id particle] (particle-maps)
              :let [unknown (remove #(or (render-plan-reads %) (known-dropped %))
                                    (keys particle))]
              :when (seq unknown)]
          {:effect effect-id :unknown (vec (sort unknown))})]
    (is (= [] (vec (distinct offenders)))
        (str "these :particle keys are neither read by the render plan nor"
             " listed as known-dropped, so they are a new silent drop: "
             (pr-str (vec (distinct offenders)))))))

(deftest known-dropped-list-is-still-accurate-test
  ;; A key that no effect writes any more must leave the list, or it becomes
  ;; a licence for a name nothing uses -- the same drift that let :owner,
  ;; :seed and :style sit in the runtime-read exemption for a reader that
  ;; does not exist.
  (let [written (into #{} (mapcat (comp keys second)) (particle-maps))
        stale (remove written known-dropped)]
    (is (= [] (vec (sort stale)))
        (str "known-dropped names particle keys no effect writes: "
             (pr-str (vec (sort stale)))))))
