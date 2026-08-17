(ns cn.li.presentation.compiler.render
  "Pure Clojure interpreter for compiled UI templates.

   This is deliberately a small render-IR producer: it reads numeric binding
   IDs from PresentationViewModel and emits immutable RenderCommand records.
   Minecraft APIs and host policy stay outside this namespace.

   Geometry comes from cn.li.presentation.core.layout, not from ad hoc
   per-node-type math here — width/height/min-*/max-*/aspect-ratio/direction/
   gap declared in a .ui.edn template are real layout inputs, not decoration."
  (:require [cn.li.presentation.core.layout :as layout])
  (:import [cn.li.presentation.compiler CompiledTemplate TemplateNode]
           [cn.li.presentation.core BindingTable PresentationViewModel]
           [cn.li.mcmod.runtime RenderCommand$GlyphRun RenderCommand$Image RenderCommand$Quad]))

(defn- binding-value [^PresentationViewModel model ^TemplateNode node attr]
  (when-let [id (get (.bindings node) (name attr))]
    (.value ^BindingTable (.bindings model) (int id))))

(defn- clamp [x lo hi]
  (max lo (min hi (double (or x 0.0)))))

(defn- ratio [value]
  (cond
    (number? value) (clamp value 0.0 1.0)
    (map? value) (clamp (or (:ratio value) (:percent value) (:value value)) 0.0 1.0)
    :else 0.0))

(defn- rect-value [value default]
  (if (map? value)
    {:x (float (or (:x value) (:left value) (:x default) 0.0))
     :y (float (or (:y value) (:top value) (:y default) 0.0))
     :w (float (or (:w value) (:width value) (:w default) 1.0))
     :h (float (or (:h value) (:height value) (:h default) 1.0))
     :rgba (int (or (:rgba value) (:color value) -1))}
    (assoc default :rgba (int (or (:rgba default) -1)))))

(defn- template-node->layout-tree
  "Bridge a compiled TemplateNode tree into the plain map shape
   cn.li.presentation.core.layout expects. Node props already use keyword
   keys (compile-node selects them straight off the source EDN map), so no
   re-keying is needed — only the Java children list needs converting."
  [^TemplateNode node]
  {:type (keyword (.type node))
   :props (into {} (.props node))
   :children (mapv template-node->layout-tree (.children node))})

;; layout/layout's output is a pure function of (template, width, height) --
;; it never reads binding values (those only affect what render-node draws
;; at an already-resolved rect). CompiledTemplate instances are resolved
;; once and reused for the template's lifetime (see
;; ac.gui.reactive.register/resolve-template's template-cache*), so a
;; per-template-identity cache here means a cooldown/CP tick that only
;; changes a bound value never re-triggers layout, matching the documented
;; static-HUD performance budget (see
;; docs/04-systems/COMBAT_VFX_PLATFORM_GAPS.md F section). Keyed by the
;; CompiledTemplate object itself (no equals/hashCode override, so this is
;; identity keying); the live template set is small and effectively
;; permanent for the app's lifetime, so this never needs eviction.
(defonce ^:private layout-cache* (atom {}))

(defn- cached-layout-tree
  [^CompiledTemplate template width height]
  (let [cached (get @layout-cache* template)]
    (if (and cached (= width (:width cached)) (= height (:height cached)))
      (:tree cached)
      (let [tree (layout/layout (template-node->layout-tree (.root template)) width height)]
        (swap! layout-cache* assoc template {:width width :height height :tree tree})
        tree))))

(declare render-node)

(defn- render-children [^TemplateNode node laid-out model]
  (mapcat (fn [child child-laid] (render-node child child-laid model))
          (.children node) (:children laid-out)))

(defn render-node [^TemplateNode node laid-out model]
  (let [{:keys [x y width height]} (:layout laid-out)
        value (binding-value model node :value)
        type (.type node)]
    (case type
      ("stack" "flex" "grid" "scroll" "portal")
      (render-children node laid-out model)

      "virtual-list"
      (let [items (vec (or (binding-value model node :items) value []))
            n (max 1 (count items))
            row-height 18.0
            visible-count (+ 2 (int (Math/ceil (/ height row-height))))
            visible-items (take visible-count items)]
        (mapcat (fn [[idx item]]
                  (let [label (if (map? item)
                                (or (:label item) (:name item) (:id item) (:title item))
                                item)
                        y* (+ y (* idx row-height))]
                    [(RenderCommand$Quad. (float x) (float y*) (float width)
                                           (float (max 1.0 (- row-height 1.0)))
                                           (unchecked-int 0x55203040))
                     (RenderCommand$GlyphRun. 0 (str (or label ""))
                                               (+ (float x) 4.0) (+ (float y*) 3.0)
                                               (unchecked-int 0xFFFFFFFF))]))
                (map-indexed vector visible-items)))

      "progress"
      (let [p (ratio value)
            bg (RenderCommand$Quad. (float x) (float y) (float width) (float height)
                                    (unchecked-int 0x55202020))
            fg (RenderCommand$Quad. (float x) (float y) (float (* width p)) (float height)
                                    (unchecked-int 0xFF35C7FF))]
        [bg fg])

      "text"
      [(RenderCommand$GlyphRun. 0 (str (or value "")) (float x) (float y)
                                  (unchecked-int 0xFFFFFFFF))]

      "textbox"
      (let [props (into {} (.props node))
            value (if (map? value) value {:text value})
            text (str (or (:text value) (:value value) (:text props)
                          (:placeholder props) ""))
            focused? (boolean (:focused? value))
            border (if focused? 0xFF55C7FF 0xFF405060)]
        [(RenderCommand$Quad. (float x) (float y) (float width) (float height)
                              (unchecked-int 0xCC101820))
         (RenderCommand$Quad. (float x) (float y) (float width) 1.0
                              (unchecked-int border))
         (RenderCommand$GlyphRun. 0 text (+ (float x) 4.0) (+ (float y) 4.0)
                                   (unchecked-int 0xFFFFFFFF))])

      "button"
      (let [props (into {} (.props node))
            ;; A bound button is opt-in: nil means that the host did not
            ;; expose this optional control for the current screen.  This
            ;; keeps the shared machine template from drawing phantom
            ;; controls on containers that do not declare button metadata.
            has-value? (or (map? value) (contains? props :label))
            value (if (map? value) value {})
            visible? (and has-value? (not (false? (:visible? value))))
            bx (float (or (:x value) (:x props) x))
            by (float (or (:y value) (:y props) y))
            bw (float (or (:w value) (:width value) (:w props) width))
            bh (float (or (:h value) (:height value) (:h props) height))
            rgba (unchecked-int (or (:rgba value) (:rgba props) 0xCC315A78))
            label (or (:label value) (:label props) "")]
        (if visible?
          [(RenderCommand$Quad. bx by bw bh rgba)
           (RenderCommand$GlyphRun. 0 (str label) (+ bx 4.0) (+ by 4.0)
                                      (unchecked-int 0xFFFFFFFF))]
          []))

      "image"
      (let [{:keys [rgba]} (rect-value value {:x x :y y :w width :h height})]
        [(RenderCommand$Image. 0 (float x) (float y) (float width) (float height) rgba)])

      "quad"
      (let [{:keys [x y w h rgba]} (rect-value value {:x x :y y :w width :h height})]
        [(RenderCommand$Quad. (float x) (float y) (float w) (float h) rgba)])

      "skill-wheel"
      (let [items (vec (or (binding-value model node :items) value []))
            selected (binding-value model node :selected)
            n (max 1 (count items))]
        (mapcat (fn [[idx item]]
                  (let [angle (* 2.0 Math/PI (/ idx n))
                        cx (+ x (* 0.5 width) (* 0.35 width (Math/cos angle)))
                        cy (+ y (* 0.5 height) (* 0.35 height (Math/sin angle)))
                        label (if (map? item) (or (:label item) (:skill-id item)) item)
                        remaining (double (or (:cooldown-remaining item) 0.0))
                        total (double (or (:cooldown-total item) 0.0))
                        on-cooldown? (and (pos? total) (pos? remaining))
                        cd-ratio (if on-cooldown? (min 1.0 (/ remaining total)) 0.0)]
                    (cond-> [(RenderCommand$Quad. (float (- cx 12.0)) (float (- cy 12.0)) 24.0 24.0
                                                   (unchecked-int (if (= idx selected) 0xCC5C8CC0 0xCC406080)))]
                      on-cooldown?
                      (conj (RenderCommand$Quad. (float (- cx 12.0)) (float (- cy 12.0))
                                                 24.0 (float (* 24.0 cd-ratio))
                                                 (unchecked-int 0x99000000)))
                      true
                      (conj (RenderCommand$GlyphRun. 0 (str label) (float (- cx 8.0)) (float (+ cy 14.0))
                                                     (unchecked-int 0xFFFFFFFF))))))
                (map-indexed vector items)))

      "modal"
      (if (or (map? value) (true? value))
        [(RenderCommand$Quad. (float x) (float y) (float width) (float height)
                              (unchecked-int 0xAA000000))]
        [])

      "composite-list"
      ;; A flat overlay: each item is a self-positioned primitive
      ;; ({:kind :quad|:image|:text, :x :y :w :h/:src/:text, :rgba}), already
      ;; screen-placed by whoever built the binding — unlike every other node
      ;; type here, items are not relative to this node's laid-out x/y/width/
      ;; height. This is deliberate: reactive_hud-style overlays (toasts,
      ;; particle bursts, HUD readouts) are absolutely positioned by their own
      ;; domain-specific layout math (see cn.li.ac.ability.client.hud), which
      ;; the presentation layout engine has no vocabulary for.
      (let [items (vec (or (binding-value model node :items) value []))]
        (keep (fn [{:keys [kind x y w h src text rgba]}]
                (case kind
                  :quad (RenderCommand$Quad. (float (or x 0.0)) (float (or y 0.0))
                                             (float (or w 1.0)) (float (or h 1.0))
                                             (unchecked-int (or rgba -1)))
                  :image (RenderCommand$Image. 0 (float (or x 0.0)) (float (or y 0.0))
                                               (float (or w 1.0)) (float (or h 1.0))
                                               (unchecked-int (or rgba -1)))
                  :text (RenderCommand$GlyphRun. 0 (str (or text "")) (float (or x 0.0)) (float (or y 0.0))
                                                 (unchecked-int (or rgba -1)))
                  nil))
              items))

      (render-children node laid-out model))))

(defn render-template
  "Render a compiled template into painter-order commands.

   `context` is a map with `:width` and `:height`; no mutable UI state is
   created here, which keeps the hot path suitable for later instance-buffer
   specialization. Layout is cached per (template, width, height) --
   render-node then just reads each node's resolved rect off the parallel
   laid-out tree, so a binding-only change (no resize) never re-runs
   layout."
  [^CompiledTemplate template ^PresentationViewModel model context]
  (let [width (double (max 1 (or (:width context) 1)))
        height (double (max 1 (or (:height context) 1)))
        laid-out (cached-layout-tree template width height)]
    (vec (render-node (.root template) laid-out model))))
