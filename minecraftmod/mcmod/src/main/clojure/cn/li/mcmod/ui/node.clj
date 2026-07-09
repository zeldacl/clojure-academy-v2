(ns cn.li.mcmod.ui.node
  "Node model & kind table. Replaces old CGUI atom-per-field map.

   Node = Java POJO with mutable primitive fields + dslots/oslots arrays.
   All access via INode Java interface (cross-ns/module safe, remap-safe).

   Pure kind definition table: S0 static immutable def (not in registry).
   Render backend :render!/:bake! installed by mc1201 via install-adapter!
   into [:platform :ui-kinds]."
  (:import [cn.li.mcmod.ui.node INode Node]
           [cn.li.mcmod.uipojo.signal ISigD ISigO]))

;; ============================================================================
;; Bitmask constants
;; ============================================================================

(def ^:const FLAG-LAYOUT-DIRTY 1)
(def ^:const FLAG-RENDER-DIRTY 2)
(def ^:const FLAG-CLIP 4)
(def ^:const FLAG-HAS-TRANSFORM 8)
(def ^:const FLAG-HOVERED 16)
(def ^:const FLAG-FOCUSED 32)

;; ============================================================================
;; Factory
;; ============================================================================

(defn create-node
  "Create Node. Props: {:x :y :w :h :scale :z :pivot-x :pivot-y
   :align-w :align-h :visible? :clip? :transform?}."
  [idx id kind props dslot-count oslot-count static-props]
  (let [{:keys [x y w h scale z pivot-x pivot-y align-w align-h]
         :or {x 0.0 y 0.0 w 0.0 h 0.0 scale 1.0 z 0.0
              pivot-x 0.0 pivot-y 0.0
              align-w :left align-h :top}}
        props
        visible? (boolean (get props :visible? true))
        clip? (boolean (get props :clip? false))
        transform? (boolean (get props :transform? false))
        flags-val (int (bit-or (if clip? FLAG-CLIP 0)
                               (if transform? FLAG-HAS-TRANSFORM 0)
                               FLAG-LAYOUT-DIRTY FLAG-RENDER-DIRTY))
        aw (byte (case align-w :center 1 :right 2 0))
        ah (byte (case align-h :middle 1 :bottom 2 0))]
    (Node. (int idx) id kind
           nil                                   ;; parent
           nil                                   ;; children (POJO allocates)
           (double x) (double y) (double w) (double h) (double scale) (double z)
           0.0 0.0 1.0                           ;; abs-x/y, cum-scale
           (double pivot-x) (double pivot-y)
           aw ah
           flags-val
           visible?
           static-props
           (double-array dslot-count)
           (object-array oslot-count))))

(defn child-count
  "O(1) child count — maintained by add-child!/remove-child!/clear-children!."
  [^INode node]
  (.getChildCount node))

(defn add-child!
  "Add child to parent's children array (auto-grow). Sets parent back-ref."
  [^INode parent ^INode child]
  (let [n (.getChildCount parent)
        ^objects old (.getChildrenArr parent)
        cap (alength old)]
    (if (< n cap)
      (aset old n child)
      (let [new-arr (make-array INode (max 8 (* 2 cap)))]
        (System/arraycopy old 0 new-arr 0 n)
        (aset new-arr n child)
        (.setChildrenArr parent new-arr)))
    (.setParentNode child parent)
    (.setChildCount parent (unchecked-inc-int n))
    parent))

(defn remove-child!
  "Remove child from parent's children array by identity."
  [^INode parent ^INode child]
  (let [n (.getChildCount parent)
        ^objects cs (.getChildrenArr parent)]
    (loop [i 0]
      (when (< i n)
        (if (identical? child (aget cs i))
          (do
            (loop [j i]
              (when (< j (dec n))
                (aset cs j (aget cs (unchecked-inc-int j)))
                (recur (unchecked-inc-int j))))
            (aset cs (dec n) nil)
            (.setChildCount parent (dec n)))
          (recur (unchecked-inc-int i))))))
  parent)

;; ============================================================================
;; Prop-writers (public — referenced by kinds table; static top-level defns)
;; ============================================================================

(defn write-fill-argb! [^INode node source]
  (let [v (.dGet ^ISigD source)]
    (when-not (== v (.getDSlot node 0))
      (.setDSlot node 0 v)
      (.setFlag node FLAG-RENDER-DIRTY))))

(defn write-outline-argb! [^INode node source]
  (let [v (.dGet ^ISigD source)]
    (when-not (== v (.getDSlot node 1))
      (.setDSlot node 1 v)
      (.setFlag node FLAG-RENDER-DIRTY))))

(defn write-tint-argb! [^INode node source]
  (let [v (.dGet ^ISigD source)]
    (when-not (== v (.getDSlot node 3))
      (.setDSlot node 3 v)
      (.setFlag node FLAG-RENDER-DIRTY))))

(defn write-hover-tint-argb! [^INode node source]
  (let [v (.dGet ^ISigD source)]
    (when-not (== v (.getDSlot node 4))
      (.setDSlot node 4 v)
      (.setFlag node FLAG-RENDER-DIRTY))))

(defn write-src! [^INode node source]
  (let [v (.sGet ^ISigO source)]
    (when-not (= v (.getOSlot node 0))
      (.setOSlot node 0 v)
      (.setFlag node FLAG-RENDER-DIRTY))))

(defn write-alpha-d! [^INode node source]
  (let [v (.dGet ^ISigD source)]
    (when-not (== v (.getDSlot node 0))
      (.setDSlot node 0 v)
      (.setFlag node FLAG-RENDER-DIRTY))))

(defn write-u-d! [^INode node source]
  (let [v (.dGet ^ISigD source)]
    (when-not (== v (.getDSlot node 1))
      (.setDSlot node 1 v)
      (.setFlag node FLAG-RENDER-DIRTY))))

(defn write-v-d! [^INode node source]
  (let [v (.dGet ^ISigD source)]
    (when-not (== v (.getDSlot node 2))
      (.setDSlot node 2 v)
      (.setFlag node FLAG-RENDER-DIRTY))))

(defn write-tex-w-d! [^INode node source]
  (let [v (.dGet ^ISigD source)]
    (when-not (== v (.getDSlot node 3))
      (.setDSlot node 3 v)
      (.setFlag node FLAG-RENDER-DIRTY))))

(defn write-tex-h-d! [^INode node source]
  (let [v (.dGet ^ISigD source)]
    (when-not (== v (.getDSlot node 4))
      (.setDSlot node 4 v)
      (.setFlag node FLAG-RENDER-DIRTY))))

(defn write-tint-rgb! [^INode node source]
  (let [rgb (cond
              (vector? source) (mapv #(double %) source)
              (and (vector? source) (= 3 (count source))) source
              :else [255.0 255.0 255.0])]
    (when-not (= rgb (.getOSlot node 1))
      (.setOSlot node 1 rgb)
      (.setFlag node FLAG-RENDER-DIRTY))))

(defn write-text! [^INode node source]
  (let [v (.sGet ^ISigO source)]
    (when-not (= v (.getOSlot node 0))
      (.setOSlot node 0 v)
      (.setFlag node FLAG-RENDER-DIRTY))))

(defn write-color-int! [^INode node source]
  (let [v (.dGet ^ISigD source)]
    (when-not (== v (.getDSlot node 0))
      (.setDSlot node 0 v)
      (.setFlag node FLAG-RENDER-DIRTY))))

(defn write-font-size-d! [^INode node source]
  (let [v (.dGet ^ISigD source)]
    (when-not (== v (.getDSlot node 0))
      (.setDSlot node 0 v)
      (.setFlag node FLAG-RENDER-DIRTY))))

(defn write-progress-d! [^INode node source]
  (let [v (.dGet ^ISigD source)]
    (when-not (== v (.getDSlot node 0))
      (.setDSlot node 0 v)
      (.setFlag node FLAG-RENDER-DIRTY))))

(defn write-hint-percent-d! [^INode node source]
  (let [v (.dGet ^ISigD source)]
    (when-not (== v (.getDSlot node 2))
      (.setDSlot node 2 v)
      (.setFlag node FLAG-RENDER-DIRTY))))

(defn write-color-stops! [^INode node source]
  (let [v (.sGet ^ISigO source)]
    (when-not (= v (.getOSlot node 0))
      (.setOSlot node 0 v)
      (.setFlag node FLAG-RENDER-DIRTY))))

(defn write-bg-src! [^INode node source]
  (let [v (.sGet ^ISigO source)]
    (when-not (= v (.getOSlot node 1))
      (.setOSlot node 1 v)
      (.setFlag node FLAG-RENDER-DIRTY))))

(defn write-fg-src! [^INode node source]
  (let [v (.sGet ^ISigO source)]
    (when-not (= v (.getOSlot node 2))
      (.setOSlot node 2 v)
      (.setFlag node FLAG-RENDER-DIRTY))))

(defn write-icon-src! [^INode node source]
  (let [v (.sGet ^ISigO source)]
    (when-not (= v (.getOSlot node 3))
      (.setOSlot node 3 v)
      (.setFlag node FLAG-RENDER-DIRTY))))

(defn write-shader-props! [^INode node source]
  (let [v (.sGet ^ISigO source)]
    (when-not (= v (.getOSlot node 0))
      (.setOSlot node 0 v)
      (.setFlag node FLAG-RENDER-DIRTY))))

(defn write-ring-progress-d! [^INode node source]
  (let [v (.dGet ^ISigD source)]
    (when-not (== v (.getDSlot node 0))
      (.setDSlot node 0 v)
      (.setFlag node FLAG-RENDER-DIRTY))))

(defn write-stops! [^INode node source]
  (let [v (.sGet ^ISigO source)]
    (when-not (= v (.getOSlot node 0))
      (.setOSlot node 0 v)
      (.setFlag node FLAG-RENDER-DIRTY))))

(defn write-scroll-offset-d! [^INode node source]
  (let [v (.dGet ^ISigD source)]
    (when-not (== v (.getDSlot node 3))
      (.setDSlot node 3 v)
      (.setFlag node FLAG-RENDER-DIRTY))))

(defn write-list-scroll-offset-d! [^INode node source]
  (let [v (.dGet ^ISigD source)]
    (when-not (== v (.getDSlot node 1))
      (.setDSlot node 1 v)
      (.setFlag node FLAG-LAYOUT-DIRTY))))

(defn write-spacing-d! [^INode node source]
  (let [v (.dGet ^ISigD source)]
    (when-not (== v (.getDSlot node 0))
      (.setDSlot node 0 v)
      (.setFlag node FLAG-LAYOUT-DIRTY))))

;; ============================================================================
;; Kind definition table (S0 static def)
;; ============================================================================

(def kinds
  {:group
   {:dslots {}
    :oslots {}
    :oslots-backend-base 0
    :prop-writers {}
    :hit? false}

   :box
   {:dslots {:fill-argb 0 :outline-argb 1 :outline-width 2 :tint-argb 3 :hover-tint-argb 4}
    :oslots {}
    :oslots-backend-base 4
    :prop-writers {:fill write-fill-argb!
                   :outline write-outline-argb!
                   :tint write-tint-argb!
                   :hover-tint write-hover-tint-argb!}
    :hit? true}

   :image
   {:dslots {:alpha 0 :u 1 :v 2 :tex-w 3 :tex-h 4}
    :oslots {:src 0 :tint 1}
    :oslots-backend-base 2
    :prop-writers {:src write-src! :alpha write-alpha-d! :tint write-tint-rgb!
                   :u write-u-d! :v write-v-d!
                   :tex-w write-tex-w-d! :tex-h write-tex-h-d!}
    :hit? true}

   :text
   {:dslots {:font-size 0 :x-offset 1 :y-offset 2}
    :oslots {:text 0 :color 1 :font 2 :align 3}
    :oslots-backend-base 8
    :prop-writers {:text write-text! :color write-color-int! :font-size write-font-size-d!}
    :hit? true}

   :progress
   {:dslots {:progress 0 :corner 1 :hint-percent 2 :scroll-offset 3 :alpha 4}
    :oslots {:color-stops 0 :bg-src 1 :fg-src 2 :icon-src 3}
    :oslots-backend-base 8
    :prop-writers {:progress write-progress-d!
                   :hint write-hint-percent-d!
                   :color-stops write-color-stops!
                   :bg-src write-bg-src!
                   :fg-src write-fg-src!
                   :icon-src write-icon-src!
                   :scroll-offset write-scroll-offset-d!}
    :hit? false}

   :crosshair
   {:dslots {:phase 0 :intensity 1}
    :oslots {}
    :oslots-backend-base 0
    :prop-writers {}
    :hit? false}

   :shader-quad
   {:dslots {}
    :oslots {:shader-props 0}
    :oslots-backend-base 4
    :prop-writers {:shader-props write-shader-props!}
    :hit? false}

   :shader-ring
   {:dslots {:progress 0 :inner 1 :outer 2}
    :oslots {:shader-props 0}
    :oslots-backend-base 4
    :prop-writers {:progress write-ring-progress-d!}
    :hit? false}

   :shader-progress
   {:dslots {:progress 0}
    :oslots {:shader-props 0}
    :oslots-backend-base 4
    :prop-writers {:progress write-ring-progress-d!}
    :hit? false}

   :gradient
   {:dslots {:alpha 0 :angle 1}
    :oslots {:stops 0}
    :oslots-backend-base 2
    :prop-writers {:stops write-stops! :alpha write-alpha-d!}
    :hit? false}

   :line
   {:dslots {:x1 0 :y1 1 :x2 2 :y2 3 :thickness 4 :alpha 5}
    :oslots {:color 0}
    :oslots-backend-base 2
    :prop-writers {:color write-color-int! :alpha write-alpha-d!}
    :hit? false}

   :list
   {:dslots {:spacing 0 :scroll-offset 1}
    :oslots {:template 0}
    :oslots-backend-base 0
    :prop-writers {:spacing write-spacing-d! :scroll-offset write-list-scroll-offset-d!}
    :hit? true}})
