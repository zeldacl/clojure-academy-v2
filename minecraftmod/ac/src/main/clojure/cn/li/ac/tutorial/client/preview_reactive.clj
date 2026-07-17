(ns cn.li.ac.tutorial.client.preview-reactive
  "Complete reactive replacement for tutorial/client/preview.clj.
   ViewGroup × SubView navigation data/state (build-view-groups and all
   pure navigation functions) is reused verbatim. Only the CGUI widget
   builders (create-icon-preview, create-recipe-preview, load-recipe-widget,
   etc.) are replaced with native node specs.

   :block-3d and :item-3d views now render as scaled ItemStacks via the
   :preview-item kind (using GuiGraphics.renderFakeItem), replacing the
   previous empty placeholder boxes with actual item model rendering."
  (:require [clojure.string :as str]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.terminal.catalog :as terminal-catalog]
            [cn.li.mcmod.client.platform-bridge :as platform-bridge]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; ViewGroup data — reused verbatim from preview.clj
;; ============================================================================

(defn- query-recipes? [item-id]
  (try
    (platform-bridge/has-recipes? item-id)
    (catch Throwable e
      (log/warn "Recipe query failed for" item-id (ex-message e))
      false)))

(defn- recipe-kind->query-kind [kind]
  (case kind
    "ImagFusor" :imag-fusor
    "MetalFormer" :metal-former
    "Smelting" :smelting
    "Crafting" :crafting
    :crafting))

(defn- find-recipe-kind-for
  "Look through ALL recipe types to find which one has recipes for this item.
   Returns [kind-string recipes] or nil."
  [item-id]
  (try
    (when-let [result (platform-bridge/find-recipes item-id)]
      (let [kinds [[:crafting "Crafting"]
                   [:smelting "Smelting"]
                   [:imag-fusor "ImagFusor"]
                   [:metal-former "MetalFormer"]]]
        (some (fn [[qk kind-name]]
                (when-let [recipes (seq (get result qk))]
                  [kind-name recipes]))
              kinds)))
    (catch Throwable _
      nil)))

(defn- expand-recipe-sub-views
  "For a view group that has :recipe or :crafting-grid sub-views, query all
   matching recipes and create one sub-view per recipe variant.  Multiple
   recipes stay as sub-views within the same view group — the left/right
   arrows switch between them (matching upstream AcademyCraft's
   RecipeHandler.recipeOfStack() → ViewGroup.getSubViews() array)."
  [vg]
  (let [svs (:sub-views vg)
        sv (first svs)
        sv-type (:type sv)]
    (if (and (= 1 (count svs))
             (or (= :recipe sv-type) (= :crafting-grid sv-type)))
      (let [item-id (:item-id sv)
            hardcoded-kind (:recipe-kind sv)
            [actual-kind recipes] (find-recipe-kind-for item-id)]
        (if recipes
          (let [kind-changed? (not= hardcoded-kind actual-kind)
                base (if kind-changed?
                       (assoc sv :recipe-kind actual-kind :type :recipe)
                       (assoc sv :type :recipe))]
            (assoc vg :sub-views
                   (mapv (fn [recipe]
                           (assoc base :recipe-input (:input recipe)
                                    :recipe-count (:count recipe)))
                         recipes)))
          ;; No recipe found → fall back to 3D item view
          (assoc vg :sub-views [(assoc sv :type :item-3d)])))
      ;; Non-recipe groups pass through unchanged
      vg)))

(defn build-view-groups
  "Build the ViewGroup list for a tutorial, matching original AcademyCraft
   TutorialInit definitions. Each group = {:tag :view|:craft :display-text ...
   :sub-views [{:type ...}]}"
  [tut-id]
  (let [raw (case tut-id
    :ores
    [{:tag :view :display-text "Constraint Metal Ore"
      :sub-views [{:type :block-3d :block-id (modid/namespaced-path "constrained_ore")}]}
     {:tag :view :display-text "Imag Silicon Ore"
      :sub-views [{:type :block-3d :block-id (modid/namespaced-path "imaginary_ore")}]}
     {:tag :view :display-text "Crystal Ore"
      :sub-views [{:type :block-3d :block-id (modid/namespaced-path "crystal_ore")}]}
     {:tag :view :display-text "Resonant Crystal Ore"
      :sub-views [{:type :block-3d :block-id (modid/namespaced-path "reso_ore")}]}
     {:tag :view :display-text "Phase Liquid"
      :sub-views [{:type :icon
                   :texture (modid/asset-path "textures/item" "phase_liquid_mat.png")
                   :tag :view}]}
     {:tag :craft :display-text "Crafting: Constraint Plate"
      :sub-views [{:type :recipe :recipe-kind "MetalFormer"
                  :item-id (modid/namespaced-path "constrained_plate")}]}
     {:tag :craft :display-text "Crafting: Imag Silicon Ingot"
      :sub-views [{:type :recipe :recipe-kind "MetalFormer"
                  :item-id (modid/namespaced-path "imag_silicon_ingot")}]}
     {:tag :craft :display-text "Crafting: Wafer"
      :sub-views [{:type :recipe :recipe-kind "MetalFormer"
                  :item-id (modid/namespaced-path "wafer")}]}
     {:tag :craft :display-text "Crafting: Imag Silicon Piece"
      :sub-views [{:type :recipe :recipe-kind "MetalFormer"
                  :item-id (modid/namespaced-path "imag_silicon_piece")}]}]

    :phase_generator
    [{:tag :craft :display-text "Crafting: Phase Generator"
      :sub-views [(if (query-recipes? (modid/namespaced-path "phase_gen"))
                    {:type :recipe :recipe-kind "ImagFusor" :item-id (modid/namespaced-path "phase_gen")}
                    {:type :item-3d :item-id (modid/namespaced-path "phase_gen")})]}]

    :solar_generator
    [{:tag :craft :display-text "Crafting: Solar Generator"
      :sub-views [(if (query-recipes? (modid/namespaced-path "solar_gen"))
                    {:type :recipe :recipe-kind "Smelting" :item-id (modid/namespaced-path "solar_gen")}
                    {:type :item-3d :item-id (modid/namespaced-path "solar_gen")})]}]

    :wind_generator
    (vec (for [[item-id display-name]
               [[(modid/namespaced-path "wind_gen_base") "Crafting: Windgen Base"]
                [(modid/namespaced-path "wind_gen_pillar") "Crafting: Windgen Pillar"]
                [(modid/namespaced-path "wind_gen_main") "Crafting: Windgen Main"]
                [(modid/namespaced-path "windgen_fan") "Crafting: Windgen Fan"]]]
           {:tag :craft :display-text display-name
            :sub-views [{:type :recipe :recipe-kind "MetalFormer"
                        :item-id item-id}]}))

    :imag_fusor
    [{:tag :craft :display-text "Crafting: Imag Fusor"
      :sub-views [{:type :recipe :recipe-kind "ImagFusor"
                  :item-id (modid/namespaced-path "imag_fusor")}]}]

    :metal_former
    [{:tag :craft :display-text "Crafting: Metal Former"
      :sub-views [{:type :recipe :recipe-kind "MetalFormer"
                  :item-id (modid/namespaced-path "metal_former")}]}]

    :terminal
    (let [base [{:tag :craft :display-text "Crafting: Terminal Installer"
                 :sub-views [{:type :recipe :recipe-kind "Smelting"
                             :item-id (modid/namespaced-path "terminal_installer")}]}]
          apps (try (terminal-catalog/ordered-apps)
                    (catch Throwable e
                      (log/warn "Failed to load terminal catalog; app tutorial entries may be missing:" (ex-message e))
                      nil))
          app-groups (mapv (fn [app]
                            (let [app-installer-id (modid/namespaced-path (str "app_"
                                                       (str/replace (name (:id app)) "-" "_")))
                                  has-recipe? (query-recipes? app-installer-id)]
                              {:tag (if has-recipe? :craft :view)
                               :display-text (str "App: " (:name app))
                               :sub-views [(if has-recipe?
                                             {:type :crafting-grid
                                              :recipe-kind "Crafting"
                                              :item-id app-installer-id}
                                             {:type :icon
                                              :texture (:icon app)
                                              :item-id (name (:id app))})]}))
                          (or apps []))]
      (into base app-groups))

    :ability_developer
    [{:tag :craft :display-text "Crafting: Portable Developer"
      :sub-views [{:type :recipe :recipe-kind "MetalFormer"
                  :item-id (modid/namespaced-path "developer_portable")}]}
     {:tag :craft :display-text "Crafting: Normal Developer"
      :sub-views [{:type :recipe :recipe-kind "MetalFormer"
                  :item-id (modid/namespaced-path "developer_normal")}]}
     {:tag :craft :display-text "Crafting: Advanced Developer"
      :sub-views [{:type :recipe :recipe-kind "MetalFormer"
                  :item-id (modid/namespaced-path "developer_advanced")}]}]

    [])
      expanded (mapv expand-recipe-sub-views raw)]
    expanded))

;; ============================================================================
;; Preview node specs — native replacement for build-preview-widget.
;; All positioned to exactly fill the 134×134 "area" node (abs computed by
;; the caller — see tutorial_reactive.clj's showWindow layout), matching the
;; CENTER/CENTER + scale math from the original XML/tutorial_windows.xml.
;; ============================================================================

(def ^:private recipe-bg
  {"ImagFusor" (modid/asset-path "textures/guis" "tutorial_fusor.png")
   "MetalFormer" (modid/asset-path "textures/guis" "tutorial_metalformer.png")
   "Smelting" (modid/asset-path "textures/guis" "tutorial_smelting.png")})

;; [x y w h scale] within the 134×134 area, CENTER/CENTER-aligned raw texture
;; size from tutorial_windows.xml (ImagFusor 196×128×0.6, MetalFormer
;; 192×192×0.5, Smelting 192×128×0.6).
(def ^:private recipe-geom
  {"ImagFusor" [8.2 28.6 196.0 128.0 0.6]
   "MetalFormer" [19.0 19.0 192.0 192.0 0.5]
   "Smelting" [9.4 28.6 192.0 128.0 0.6]})

(def ^:private crafting-grid-bg
  (modid/asset-path "textures/guis" "tutorial/crafting_grid.png"))

;; ============================================================================
;; Recipe slot layout — slot positions within the 134×134 preview area,
;; hand-derived from tutorial_windows.xml slot coords × scale + recipe-geom offset.
;; ============================================================================

(def ^:private recipe-slots
  "Per recipe-kind: list of {:id :x :y :w :h :role} for each slot within the
   134×134 preview area.  :role is :input or :output."
  {"ImagFusor"    [{:id :slot-in  :x 19.6  :y 66.1  :w 19.2 :h 18.6 :role :input}
                   {:id :slot-out :x 96.4  :y 66.1  :w 19.2 :h 18.6 :role :output}]
   "MetalFormer"  [{:id :slot-in  :x 24.65 :y 63.25 :w 12.5 :h 12.5 :role :input}
                   {:id :slot-out :x 96.65 :y 63.25 :w 12.5 :h 12.5 :role :output}]
   "Smelting"     [{:id :slot-in  :x 27.4  :y 54.52 :w 19.2 :h 19.2 :role :input}
                   {:id :slot-out :x 83.38 :y 54.52 :w 19.2 :h 19.2 :role :output}]})

(defn- recipe-preview-spec
  "Build a recipe preview spec: machine background + item renders in slots.
   Uses recipe data from the sub-view (pre-queried by expand-recipe-sub-views)
   or falls back to platform-bridge/first-recipe-for for single-recipe views."
  [view id]
  (let [kind (:recipe-kind view)
        item-id (:item-id view)
        input-items (or (:recipe-input view)
                        (try (when-let [r (platform-bridge/first-recipe-for
                                           item-id (recipe-kind->query-kind kind))]
                               (:input r))
                             (catch Throwable _ nil))
                        [])
        output-item (or (:item-id view) item-id)]
    (if (= kind "Crafting")
      ;; Crafting table: 196×128 widget scaled 0.6 placed at ImagFusor offset.
      ;; Slot coords from upstream RecipeHandler.CraftingGridDisplay:
      ;;   STEP=43, input pos(5+col*43, 5+row*43), StackDisplay 32×34
      ;;   output pos(153, 49), StackDisplay 32×34
      ;; Scaled by 0.6 + offset [8.2, 28.6] into 134×134 preview.
      (let [step (* 43.0 0.6)      ;; 25.8
            base-x (+ 8.2 (* 5.0 0.6))  ;; 11.2
            base-y (+ 28.6 (* 5.0 0.6)) ;; 31.6
            slot-w (* 32.0 0.6)   ;; 19.2
            slot-h (* 34.0 0.6)   ;; 20.4
            out-x (+ 8.2 (* 153.0 0.6)) ;; 100.0
            out-y (+ 28.6 (* 49.0 0.6)) ;; 58.0
            slot-children
            (into []
              (keep-indexed
                (fn [i input-id]
                  (when input-id
                    (let [col (mod i 3) row (quot i 3)]
                      {:kind :preview-item
                       :props {:id (keyword (str (name id) "-in-" i))
                               :x (+ base-x (* col step))
                               :y (+ base-y (* row step))
                               :w slot-w :h slot-h
                               :item-id (str input-id)}})))
                input-items))]
        {:kind :group :props {:id id :x 0.0 :y 0.0 :w 134.0 :h 134.0}
         :children (into [{:kind :image :props {:id (keyword (str (name id) "-bg"))
                                                 :x 8.2 :y 28.6 :w 196.0 :h 128.0 :scale 0.6
                                                 :src crafting-grid-bg}}]
                         (conj slot-children
                               (when output-item
                                 {:kind :preview-item
                                  :props {:id (keyword (str (name id) "-out"))
                                          :x out-x :y out-y :w slot-w :h slot-h
                                          :item-id (str output-item)}})))})
      ;; Machine GUI: background image + items in defined slot positions
      (let [[x y w h scale] (get recipe-geom kind [0.0 0.0 134.0 134.0 1.0])
            bg-src (get recipe-bg kind)
            slots (get recipe-slots kind [])
            slot-children
            (into []
              (keep (fn [slot]
                      (let [slot-item (case (:role slot)
                                        :input (first input-items)
                                        :output output-item
                                        nil)]
                        (when slot-item
                          {:kind :preview-item
                           :props {:id (keyword (str (name id) "-" (name (:id slot))))
                                   :x (:x slot) :y (:y slot) :w (:w slot) :h (:h slot)
                                   :item-id (str slot-item)}}))))
                  slots)]
        {:kind :group :props {:id id :x 0.0 :y 0.0 :w 134.0 :h 134.0}
         :children (into [{:kind :image :props {:id (keyword (str (name id) "-bg"))
                                                 :x x :y y :w w :h h :scale scale
                                                 :src bg-src}}]
                         slot-children)}))))

(defn build-preview-spec
  "Build a native node spec for a single sub-view, positioned relative to
   the 134×134 preview \"area\" origin (i.e. x/y are area-local, caller adds
   the area's own absolute offset when placing this as a child)."
  [view id]
  (case (:type view)
    :icon
    ;; Original renders a 1×1 textured quad centered with scale=1 in a 0.75×
    ;; perspective viewport — ~75% of preview area.  Reactive uses orthographic
    ;; so render at 64×64 centered in the 134×134 area.
    (let [icon-size 64.0
          offset (/ (- 134.0 icon-size) 2.0)]
      {:kind :image :props {:id id :x offset :y offset :w icon-size :h icon-size
                             :src (:texture view)}})

    :recipe
    (recipe-preview-spec view id)

    :crafting-grid
    (recipe-preview-spec view id)

    :block-3d
    {:kind :preview-3d :props {:id id :x 0.0 :y 0.0 :w 134.0 :h 134.0
                                :render-type :block
                                :block-id (str (:block-id view))
                                :rotation-speed 1.0 :scale 0.8 :y-offset 0.0}}

    :item-3d
    {:kind :preview-3d :props {:id id :x 0.0 :y 0.0 :w 134.0 :h 134.0
                                :render-type :item
                                :item-id (str (:item-id view))
                                :rotation-speed 0.0 :scale 1.0 :y-offset 0.0}}

    {:kind :box :props {:id id :x 0.0 :y 0.0 :w 134.0 :h 134.0 :fill 0x00000000}}))

;; ============================================================================
;; Navigation state — two-level (ViewGroup × SubView), reused verbatim
;; ============================================================================

(defn create-preview-state [tut-id]
  (let [groups (build-view-groups tut-id)]
    {:view-groups groups :group-index 0 :sub-index 0}))

(defn current-view-group [state*]
  (let [{:keys [view-groups group-index]} @state*
        idx (or group-index 0)
        i (max 0 (min (dec (max 1 (count view-groups))) idx))]
    (when (seq view-groups)
      (nth view-groups i nil))))

(defn current-sub-view [state*]
  (when-let [vg (current-view-group state*)]
    (let [svs (or (:sub-views vg) [])
          sub-index (:sub-index @state* 0)
          idx (or sub-index 0)
          i (max 0 (min (dec (max 1 (count svs))) idx))]
      (when (seq svs)
        (nth svs i nil)))))

(defn cycle-sub-view! [state* direction]
  (when-let [vg (current-view-group state*)]
    (let [cnt (count (or (:sub-views vg) []))]
      (if (zero? cnt)
        0
        (let [delta (if (= direction :next) 1 -1)
              new-idx (mod (+ (:sub-index @state*) delta) cnt)]
          (swap! state* assoc :sub-index new-idx)
          new-idx)))))

(defn switch-view-group! [state* group-index]
  (swap! state* assoc :group-index group-index :sub-index 0)
  group-index)

(def tag-textures
  {:craft (modid/asset-path "textures/guis" "icons/icon_craft.png")
   :smelt (modid/asset-path "textures/guis" "icons/icon_smelt.png")
   :view  (modid/asset-path "textures/guis" "icons/icon_view.png")})

(defn current-tag [state*]
  (or (:tag (current-view-group state*)) :view))

(defn display-text [state*]
  (or (:display-text (current-view-group state*)) ""))
