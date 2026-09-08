(ns cn.li.platform.neutral.world-render-stage
  "Opaque platform seam for content draws that must land AFTER the translucent
   terrain layer.

   Block entities render before the translucent terrain, so a block whose
   geometry has to composite over an opaque-but-later surface (the Imag Phase
   pool's black fluid top, for example) cannot draw in the BE pass at all. The
   content renderer queues its cells during the BE pass and contributes a drain
   here; the loader's post-translucent stage hook (forge/neoforge:
   RenderLevelStageEvent AFTER_TRANSLUCENT_BLOCKS / AFTER_PARTICLES; fabric:
   WorldRenderEvents AFTER_TRANSLUCENT) calls `drain-all!` once per frame.

   Like cn.li.platform.neutral.vfx, this namespace knows only the callback
   shape; it never requires AC or mcmod namespaces. Content installs its drain
   through `requiring-resolve` at renderer registration, so no static edge runs
   from a loader into content.")

;; Drains are installed once at client renderer registration and read on every
;; stage dispatch (up to ~9 per frame), so the hot side is a plain Var root
;; holding an already-flattened vector -- no map/seq walk, no atom deref.
;; `by-id` only exists so a re-registration replaces rather than duplicates.
(def ^:private registry {:by-id {} :fns []})

(defn install-drain!
  "Register `drain-fn` under `drain-id` for the post-translucent stage.

   `drain-fn` takes the stage context map
   `{:pose-stack _ :buffer-source _ :camera-pos {:x _ :y _ :z _}}` and returns
   the number of items it drew, so the loader can skip flushing the buffer
   source when nothing was submitted. Re-installing the same id replaces the
   previous fn (development reload, or a renderer registered twice)."
  [drain-id drain-fn]
  (when-not (ifn? drain-fn)
    (throw (ex-info "World render stage drain must be a function"
                    {:drain-id drain-id :value drain-fn})))
  (alter-var-root #'registry
                  (fn [current]
                    (let [by-id (assoc (:by-id current) drain-id drain-fn)]
                      {:by-id by-id :fns (vec (vals by-id))})))
  nil)

(defn remove-drain! [drain-id]
  (alter-var-root #'registry
                  (fn [current]
                    (let [by-id (dissoc (:by-id current) drain-id)]
                      {:by-id by-id :fns (vec (vals by-id))})))
  nil)

(defn reset-drains-for-test! []
  (alter-var-root #'registry (constantly {:by-id {} :fns []}))
  nil)

(defn installed?
  "True once some content has contributed a drain.

   Loaders call this as the first guard on every stage dispatch, so it must not
   allocate: `count` on the cached vector is O(1), `seq` would not be."
  []
  (pos? (count (:fns registry))))

(defn drain-all!
  "Run every installed drain with `context` and return how many items were
   drawn in total.

   Returning 0 is the normal case: the stage fires several times per frame and
   the first call empties the content-side queues. Callers flush the buffer
   source only on a positive result -- see the loader hook for why flushing at
   the wrong moment loses the geometry entirely."
  [context]
  (let [fns (:fns registry)
        n (count fns)]
    (if (zero? n)
      0
      (loop [i 0
             drawn 0]
        (if (< i n)
          (let [drain (nth fns i)
                result (drain context)]
            (recur (inc i) (+ drawn (long (or result 0)))))
          drawn)))))
