(ns cn.li.ac.tutorial.client.preview
  "CLIENT-ONLY: Tutorial preview system with ViewGroup × SubView two-level
  navigation matching original AcademyCraft TutorialInit definitions.

  Each tutorial has a list of ViewGroups (tag tabs at bottom of showWindow).
  Each ViewGroup has sub-views (cycled with left/right arrow buttons).

  ViewGroup per tutorial aligned to original TutorialInit.java:
    ores:            9 groups  (4 drawBlock + 1 displayIcon + 4 recipes)
    phase_generator: 1 group   (recipes)
    solar_generator: 1 group   (recipes)
    wind_generator:  4 groups  (recipes ×4)
    imag_fusor:      1 group   (recipes)
    metal_former:    1 group   (recipes)
    terminal:        1+ groups (recipes, dynamically extended)
    ability_developer: 3 groups (recipes ×3)
    others:          0 groups"
  (:require [cn.li.ac.config.modid :as modid]
            [cn.li.ac.terminal.catalog :as terminal-catalog]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.xml-parser :as xml-parser]
            [cn.li.mcmod.client.platform-bridge :as platform-bridge]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Recipe window loading (from tutorial_windows.xml)
;; ============================================================================

(def ^:private recipe-widget-cache
  "Cache for parsed recipe display widgets from tutorial_windows.xml.
  Original widgets are cached; each use returns a clone."
  (atom {}))

(defn- load-recipe-widget
  "Load a named recipe display widget from tutorial_windows.xml.
  Returns a cloned widget (never the cached original) or nil."
  [widget-name]
  (let [parse! (fn []
                 (try
                   (let [path (modid/asset-path "guis" "tutorial_windows.xml")
                         root (xml-parser/read-xml path)]
                     (when root
                       ;; Use cgui-core/get-widgets for reliable child access
                       (let [widgets (cgui-core/get-widgets root)
                             found (some (fn [w]
                                           (when (= widget-name (cgui-core/get-name w))
                                             w))
                                         widgets)]
                         (or found
                             ;; Fallback: search by recursive name match
                             (some (fn [w]
                                     (when (= widget-name (cgui-core/get-name w))
                                       w))
                                   (mapcat #(cons % (cgui-core/get-widgets %)) widgets))))))
                   (catch Throwable e
                     (log/stacktrace (str "Failed to load recipe widget " widget-name) e)
                     nil)))
        widget (if-let [cached (get @recipe-widget-cache widget-name)]
                 cached
                 (let [w (parse!)]
                   (when w (swap! recipe-widget-cache assoc widget-name w))
                   w))]
    ;; Clone to avoid shared widget instance across previews
    (when widget
      (try
        (cgui-core/copy-widget widget)
        (catch Throwable e
          (log/stacktrace (str "Failed to clone recipe widget, re-parsing: " widget-name) e)
          (parse!))))))

;; ============================================================================
;; Preview widget builders
;; ============================================================================

(defn- create-icon-preview
  "Create a simple icon preview widget for the given texture path."
  [texture-path]
  (let [w (cgui-core/create-widget :pos [0 0] :size [134 134])]
    (comp/add-component! w (comp/draw-texture texture-path))
    w))

(defn- apply-recipe-detail-widgets!
  "Show and populate recipe-detail child widgets that were previously hidden.

  Custom RecipeType registration (ModRecipeTypes) now supports ImagFusor and
  MetalFormer recipes via the 1.20.1 RecipeManager. Detail widgets are shown
  unconditionally for these machine types (matching upstream AcademyCraft).

  - MetalFormer 'mode' widget: shows etch/incise/plate/refine mode icon.
    Default texture set to white placeholder (mode textures TBD as assets).
  - ImagFusor 'amount' widget: shows consumeLiquid in mB (uses XML default
    placeholder value; populated with real data when recipe is resolved).
  - ImagFusor 'progress' widget: shows animated processing progress bar.
  - Smelting: no extra widgets."
  [recipe-widget recipe-kind]
  (case recipe-kind
    "MetalFormer" (when-let [mode (cgui-core/find-widget recipe-widget "mode")]
                    (cgui-core/set-visible! mode true)
                    ;; Replace LambdaLib2 missing.png with valid placeholder texture
                    (when-let [dt (comp/get-drawtexture-component mode)]
                      (swap! (:state dt) assoc :texture nil :color 0x00FFFFFF)))
    "ImagFusor" (do (when-let [amount (cgui-core/find-widget recipe-widget "amount")]
                      (cgui-core/set-visible! amount true))
                    (when-let [progress (cgui-core/find-widget recipe-widget "progress")]
                      (cgui-core/set-visible! progress true)))
    "Smelting"   nil)
  recipe-widget)

(defn- create-recipe-preview
  "Load a recipe display widget from tutorial_windows.xml.
  `recipe-kind` is the widget name: \"ImagFusor\", \"MetalFormer\", or \"Smelting\".
  Shows machine-specific detail widgets (mode, amount, progress) now that
  custom RecipeType registration supports ImagFusor and Metal Former."
  [recipe-kind]
  (let [kind-str (name recipe-kind)]
    (if-let [w (load-recipe-widget kind-str)]
      (apply-recipe-detail-widgets! w kind-str)
      (do (log/warn "Recipe widget not found in tutorial_windows.xml, using text fallback:" kind-str)
          (let [w (cgui-core/create-widget :pos [20 20] :size [94 94])]
            (comp/add-component! w
                                 (comp/text-box
                                  :text (str "Recipe: " kind-str)
                                  :font-size 8.0
                                  :color 0xFFAAAAAA))
            w)))))

(defn- create-block-preview-widget
  "Create a placeholder widget for 3D block preview.
  Actual rendering is done by the forge bridge layer."
  [_block-id]
  (let [w (cgui-core/create-widget :pos [0 0] :size [134 134])]
    (cgui-core/set-name! w "preview-block")
    w))

(defn- create-item-preview-widget
  "Create a placeholder widget for 3D item preview.
  Actual rendering is done by the forge bridge layer."
  [_item-id]
  (let [w (cgui-core/create-widget :pos [0 0] :size [134 134])]
    (cgui-core/set-name! w "preview-item")
    w))

(def ^:private crafting-grid-bg
  (modid/asset-path "textures/guis" "tutorial/crafting_grid.png"))

(defn- create-crafting-grid-preview
  "Create a crafting table recipe grid widget.
  Shows a 3x3 grid background; individual item renders are
  handled by the forge bridge overlay (like :recipe type)."
  [_item-id]
  (let [w (cgui-core/create-widget :pos [10 5] :size [114 114])]
    (comp/add-component! w (comp/draw-texture crafting-grid-bg))
    (cgui-core/set-name! w "preview-grid")
    w))

;; ============================================================================
;; ViewGroup data — aligned to original TutorialInit.java
;; ============================================================================

(defn- query-recipes?
  "Check if the forge recipe query is available and has recipes for item-id."
  [item-id]
  (try
    (platform-bridge/has-recipes? item-id)
    (catch Throwable e
      (log/warn "Recipe query failed for" item-id (ex-message e))
      false)))

(defn build-view-groups
  "Build the ViewGroup list for a tutorial, matching original AcademyCraft
  TutorialInit definitions. Each group = {:tag :view|:craft :display-text ...
  :sub-views [{:type ...}]}"
  [tut-id]
  (case tut-id
    :ores
    [{:tag :view :display-text "Constraint Metal Ore"
      :sub-views [{:type :block-3d :block-id "my_mod:constrained_ore"}]}
     {:tag :view :display-text "Imag Silicon Ore"
      :sub-views [{:type :block-3d :block-id "my_mod:imaginary_ore"}]}
     {:tag :view :display-text "Crystal Ore"
      :sub-views [{:type :block-3d :block-id "my_mod:crystal_ore"}]}
     {:tag :view :display-text "Resonant Crystal Ore"
      :sub-views [{:type :block-3d :block-id "my_mod:reso_ore"}]}
     {:tag :view :display-text "Phase Liquid"
      :sub-views [{:type :icon
                   :texture (modid/asset-path "textures/items" "phase_liquid_mat.png")
                   :tag :view}]}
     {:tag :craft :display-text "Crafting: Constraint Plate"
      :sub-views [{:type :recipe :recipe-kind "MetalFormer"
                  :item-id "my_mod:constrained_plate"}]}
     {:tag :craft :display-text "Crafting: Imag Silicon Ingot"
      :sub-views [{:type :recipe :recipe-kind "MetalFormer"
                  :item-id "my_mod:imag_silicon_ingot"}]}
     {:tag :craft :display-text "Crafting: Wafer"
      :sub-views [{:type :recipe :recipe-kind "MetalFormer"
                  :item-id "my_mod:wafer"}]}
     {:tag :craft :display-text "Crafting: Imag Silicon Piece"
      :sub-views [{:type :recipe :recipe-kind "MetalFormer"
                  :item-id "my_mod:imag_silicon_piece"}]}]

    :phase_generator
    [{:tag :craft :display-text "Crafting: Phase Generator"
      :sub-views [(if (query-recipes? "my_mod:phase_gen")
                    {:type :recipe :recipe-kind "ImagFusor" :item-id "my_mod:phase_gen"}
                    {:type :item-3d :item-id "my_mod:phase_gen"})]}]

    :solar_generator
    [{:tag :craft :display-text "Crafting: Solar Generator"
      :sub-views [(if (query-recipes? "my_mod:solar_gen")
                    {:type :recipe :recipe-kind "Smelting" :item-id "my_mod:solar_gen"}
                    {:type :item-3d :item-id "my_mod:solar_gen"})]}]

    :wind_generator
    (vec (for [[item-id display-name]
               [["my_mod:windgen_base" "Crafting: Windgen Base"]
                ["my_mod:windgen_pillar" "Crafting: Windgen Pillar"]
                ["my_mod:windgen_main" "Crafting: Windgen Main"]
                ["my_mod:windgen_fan" "Crafting: Windgen Fan"]]]
           {:tag :craft :display-text display-name
            :sub-views [{:type :recipe :recipe-kind "MetalFormer"
                        :item-id item-id}]}))

    :imag_fusor
    [{:tag :craft :display-text "Crafting: Imag Fusor"
      :sub-views [{:type :recipe :recipe-kind "ImagFusor"
                  :item-id "my_mod:imag_fusor"}]}]

    :metal_former
    [{:tag :craft :display-text "Crafting: Metal Former"
      :sub-views [{:type :recipe :recipe-kind "MetalFormer"
                  :item-id "my_mod:metal_former"}]}]

    :terminal
    (let [base [{:tag :craft :display-text "Crafting: Terminal Installer"
                 :sub-views [{:type :recipe :recipe-kind "Smelting"
                             :item-id "my_mod:terminal_installer"}]}]
          apps (try (terminal-catalog/ordered-apps)
                    (catch Throwable e
                      (log/warn "Failed to load terminal catalog; app tutorial entries may be missing:" (ex-message e))
                      nil))
          app-groups (mapv (fn [app]
                            (let [app-installer-id (str "my_mod:app_"
                                                       (clojure.string/replace (name (:id app)) "-" "_"))
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
                  :item-id "my_mod:developer_portable"}]}
     {:tag :craft :display-text "Crafting: Normal Developer"
      :sub-views [{:type :recipe :recipe-kind "MetalFormer"
                  :item-id "my_mod:developer_normal"}]}
     {:tag :craft :display-text "Crafting: Advanced Developer"
      :sub-views [{:type :recipe :recipe-kind "MetalFormer"
                  :item-id "my_mod:developer_advanced"}]}]

    []))

(defn build-preview-widget
  "Build a CGUI widget for a single sub-view."
  [view]
  (case (:type view)
    :icon           (create-icon-preview (:texture view))
    :recipe         (create-recipe-preview (:recipe-kind view))
    :block-3d       (create-block-preview-widget (:block-id view))
    :item-3d        (create-item-preview-widget (:item-id view))
    :crafting-grid  (create-crafting-grid-preview (:item-id view))
    nil))

;; ============================================================================
;; Navigation state — two-level (ViewGroup × SubView)
;; ============================================================================

(defn create-preview-state
  "Create mutable preview state for a tutorial entry.
  Returns an atom with {:view-groups [...] :group-index 0 :sub-index 0}."
  [tut-id]
  (let [groups (build-view-groups tut-id)]
    (atom {:view-groups groups
           :group-index 0
           :sub-index 0})))

(defn current-view-group
  "Get the currently-selected ViewGroup from state."
  [state*]
  (let [{:keys [view-groups group-index]} @state*
        idx (or group-index 0)
        i (max 0 (min (dec (max 1 (count view-groups))) idx))]
    (when (seq view-groups)
      (nth view-groups i nil))))

(defn current-sub-view
  "Get the currently-selected sub-view within the current ViewGroup."
  [state*]
  (when-let [vg (current-view-group state*)]
    (let [{:keys [sub-views sub-index]} vg
          svs (or sub-views [])
          idx (or sub-index 0)
          i (max 0 (min (dec (max 1 (count svs))) idx))]
      (when (seq svs)
        (nth svs i nil)))))

(defn cycle-sub-view!
  "Advance or retreat the sub-view index within the current ViewGroup.
  Wraps around. Returns the new index."
  [state* direction]
  (when-let [vg (current-view-group state*)]
    (let [cnt (count (or (:sub-views vg) []))]
      (if (zero? cnt)
        0
        (let [delta (if (= direction :next) 1 -1)
              new-idx (mod (+ (:sub-index @state*) delta) cnt)]
          (swap! state* assoc :sub-index new-idx)
          new-idx)))))

(defn switch-view-group!
  "Switch to a different ViewGroup by index. Resets sub-index to 0."
  [state* group-index]
  (swap! state* assoc :group-index group-index :sub-index 0)
  group-index)

;; ============================================================================
;; Tag display
;; ============================================================================

(def tag-textures
  "Texture paths for ViewGroup tag icons (matching original AC)."
  {:craft (modid/asset-path "textures/guis" "icons/icon_craft.png")
   :smelt (modid/asset-path "textures/guis" "icons/icon_smelt.png")
   :view  (modid/asset-path "textures/guis" "icons/icon_view.png")})

(defn current-tag
  "Get the tag keyword for the current ViewGroup."
  [state*]
  (or (:tag (current-view-group state*)) :view))

(defn display-text
  "Get the display/hover text for the current ViewGroup."
  [state*]
  (or (:display-text (current-view-group state*)) ""))

;; For backward compat during migration — returns flat specs list
(defn current-preview-spec
  "Get the currently-selected sub-view. Legacy alias for current-sub-view."
  [state*]
  (current-sub-view state*))

(defn cycle-preview!
  "Legacy alias for cycle-sub-view!."
  [state* direction]
  (cycle-sub-view! state* direction))
