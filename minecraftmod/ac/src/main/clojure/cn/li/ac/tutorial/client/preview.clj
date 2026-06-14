(ns cn.li.ac.tutorial.client.preview
  "CLIENT-ONLY: Tutorial preview system — item/block icons, recipe displays,
  and ViewGroup navigation.

  Original AcademyCraft used ViewGroups (drawsBlock, drawsItem, recipes,
  displayIcon, displayModel) with 3D rendered models and dynamic recipe
  queries.  We replace 3D rendering with CGUI draw-texture icons and use
  the existing tutorial_windows.xml CGUI templates for recipe displays.

  Each tutorial entry can define :preview-specs — a vector of descriptor
  maps controlling what appears in the right-panel show-window.

  Preview spec shapes:
    {:type :icon  :texture \"my_mod:textures/items/foo.png\"
                  :tag :view   :display-text \"Foo Block\"}
    {:type :recipe :recipe-kind :imag-fusor|:metal-former|:smelting
                  :tag :craft   :display-text \"Crafting: Foo\"}"
  (:require [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Recipe window loading (from tutorial_windows.xml)
;; ============================================================================

(def ^:private recipe-widget-cache
  "Cache for loaded recipe display widgets from tutorial_windows.xml."
  (atom {}))

(defn- load-recipe-widget
  "Load a named recipe display widget from tutorial_windows.xml.
  Returns the widget (cloned) or nil if not found."
  [widget-name]
  (if-let [cached (get @recipe-widget-cache widget-name)]
    cached
    (try
      (when-let [read-xml (requiring-resolve 'cn.li.mcmod.gui.xml-parser/read-xml)]
        (let [path (modid/asset-path "guis" "tutorial_windows.xml")
              root (read-xml path)]
          (when root
            ;; tutorial_windows.xml has a Root with named child widgets
            (let [widgets (:children root)
                  found (some (fn [w]
                                (when (= widget-name (cgui-core/get-name w))
                                  w))
                              (or widgets []))]
              (when found
                (swap! recipe-widget-cache assoc widget-name found)
                found)))))
      (catch Throwable e
        (log/warn "Failed to load recipe widget" widget-name (ex-message e))
        nil))))

;; ============================================================================
;; Preview widget builders
;; ============================================================================

(defn- create-icon-preview
  "Create a simple icon preview widget for the given texture path."
  [texture-path]
  (let [w (cgui-core/create-widget :pos [0 0] :size [134 134])]
    (comp/add-component! w (comp/draw-texture texture-path))
    w))

(defn- create-recipe-preview
  "Load a recipe display widget from tutorial_windows.xml.
  `recipe-kind` is the widget name: \"ImagFusor\", \"MetalFormer\", or \"Smelting\"."
  [recipe-kind]
  (or (load-recipe-widget (name recipe-kind))
      (let [w (cgui-core/create-widget :pos [20 20] :size [94 94])]
        (comp/add-component! w
                             (comp/text-box
                              :text (str "Recipe: " (name recipe-kind))
                              :font-size 8.0
                              :color 0xFFAAAAAA))
        w)))

;; ============================================================================
;; Preview data
;; ============================================================================

(defn- query-recipes?
  "Check if the forge recipe query is available and has recipes for item-id."
  [item-id]
  (try
    (when-let [has-fn (requiring-resolve
                       'cn.li.forge1201.integration.recipe-query/has-recipes?)]
      (has-fn item-id))
    (catch Throwable _ false)))

(defn- build-preview-specs
  "Build preview specs for a tutorial entry.
  Queries Minecraft recipe registry dynamically (like original AC RecipeHandler).
  Falls back to item icon previews when no recipes are found."
  [tut-id]
  (case tut-id
    :ores
    (concat
      [{:type :icon :item-id "my_mod:constrained_ore"
        :texture (modid/asset-path "textures/block" "constraint_metal.png")
        :tag :view :display-text "Constraint Metal Ore"}
       {:type :icon :item-id "my_mod:imaginary_ore"
        :texture (modid/asset-path "textures/block" "imaginary_ore.png")
        :tag :view :display-text "Imag Silicon Ore"}
       {:type :icon :item-id "my_mod:crystal_ore"
        :texture (modid/asset-path "textures/block" "crystal_ore.png")
        :tag :view :display-text "Crystal Ore"}
       {:type :icon :item-id "my_mod:reso_ore"
        :texture (modid/asset-path "textures/block" "reso_ore.png")
        :tag :view :display-text "Resonant Crystal Ore"}]
      (when (query-recipes? "my_mod:constrained_plate")
        [{:type :recipe :recipe-kind "MetalFormer" :tag :craft
          :display-text "Crafting: Constraint Plate" :item-id "my_mod:constrained_plate"}]))

    :phase_generator
    (if (query-recipes? "my_mod:phase_gen")
      [{:type :recipe :recipe-kind "ImagFusor" :tag :craft
        :display-text "Crafting: Phase Generator" :item-id "my_mod:phase_gen"}]
      [{:type :icon :item-id "my_mod:phase_gen"
        :texture (modid/asset-path "textures/block" "phase_gen.png")
        :tag :view :display-text "Phase Generator"}])

    :solar_generator
    (if (query-recipes? "my_mod:solar_gen")
      [{:type :recipe :recipe-kind "Smelting" :tag :craft
        :display-text "Crafting: Solar Generator" :item-id "my_mod:solar_gen"}]
      [{:type :icon :item-id "my_mod:solar_gen"
        :texture (modid/asset-path "textures/block" "solar_gen.png")
        :tag :view :display-text "Solar Generator"}])

    :wind_generator
    (if (query-recipes? "my_mod:windgen_base")
      [{:type :recipe :recipe-kind "MetalFormer" :tag :craft
        :display-text "Crafting: Wind Generator" :item-id "my_mod:windgen_main"}]
      [{:type :icon :item-id "my_mod:windgen_base"
        :texture (modid/asset-path "textures/block" "windgen_base.png")
        :tag :view :display-text "Wind Generator"}])

    :imag_fusor
    [{:type :recipe :recipe-kind "ImagFusor" :tag :craft
      :display-text "Crafting: Imag Fusor" :item-id "my_mod:imag_fusor"}]

    :metal_former
    [{:type :recipe :recipe-kind "MetalFormer" :tag :craft
      :display-text "Crafting: Metal Former" :item-id "my_mod:metal_former"}]

    :terminal
    [{:type :recipe :recipe-kind "Smelting" :tag :craft
      :display-text "Crafting: Terminal Installer" :item-id "my_mod:terminal_installer"}]

    :ability_developer
    [{:type :recipe :recipe-kind "MetalFormer" :tag :craft
      :display-text "Crafting: Ability Developer" :item-id "my_mod:developer_normal"}]

    []))

(defn build-preview-widget
  "Build a CGUI widget for a single preview spec."
  [spec]
  (case (:type spec)
    :icon   (create-icon-preview (:texture spec))
    :recipe (create-recipe-preview (:recipe-kind spec))
    nil))

;; ============================================================================
;; Navigation state
;; ============================================================================

(defn create-preview-state
  "Create mutable preview state for a tutorial entry.
  Returns an atom with {:specs [...] :index 0}."
  [tut-id]
  (let [specs (build-preview-specs tut-id)]
    (atom {:specs specs
           :index 0})))

(defn current-preview-spec
  "Get the currently-selected preview spec from state."
  [state*]
  (let [{:keys [specs index]} @state*
        i (max 0 (min (dec (count specs)) index))]
    (when (seq specs)
      (nth specs i nil))))

(defn cycle-preview!
  "Advance or retreat the preview index.  Wraps around.
  Returns the new index."
  [state* direction]
  (let [{:keys [specs index]} @state*
        cnt (count specs)]
    (if (zero? cnt)
      0
      (let [new-idx (mod (+ index (if (= direction :next) 1 -1)) cnt)]
        (swap! state* assoc :index new-idx)
        new-idx))))

;; ============================================================================
;; Tag display
;; ============================================================================

(def tag-textures
  "Texture paths for ViewGroup tag icons (matching original AC)."
  {:craft (modid/asset-path "textures/guis" "icons/icon_craft.png")
   :smelt (modid/asset-path "textures/guis" "icons/icon_smelt.png")
   :view  (modid/asset-path "textures/guis" "icons/icon_view.png")})

(defn current-tag
  "Get the tag keyword for the current preview spec."
  [state*]
  (or (:tag (current-preview-spec state*)) :view))

(defn display-text
  "Get the display/hover text for the current preview spec."
  [state*]
  (or (:display-text (current-preview-spec state*)) ""))
