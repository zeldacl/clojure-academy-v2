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
            [cn.li.mcmod.ui.xml :as ui-xml]
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

(defn build-view-groups
  "Build the ViewGroup list for a tutorial, matching original AcademyCraft
   TutorialInit definitions. Each group = {:tag :view|:craft :display-text ...
   :sub-views [{:type ...}]}"
  [tut-id]
  (case tut-id
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
                   :texture (modid/asset-path "textures/items" "phase_liquid_mat.png")
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
               [[(modid/namespaced-path "windgen_base") "Crafting: Windgen Base"]
                [(modid/namespaced-path "windgen_pillar") "Crafting: Windgen Pillar"]
                [(modid/namespaced-path "windgen_main") "Crafting: Windgen Main"]
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

    []))

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
;; Recipe widget spec cache — loaded once from tutorial_windows.xml
;; ============================================================================

(defonce ^:private recipe-widget-specs
  (delay
    (try
      (let [spec (ui-xml/load-spec (modid/namespaced-path "guis/new/tutorial_windows.xml"))
            children (:children spec)]
        (reduce (fn [m child]
                  (if-let [cid (:id child)]
                    (assoc m (name cid) child)
                    m))
                {} children))
      (catch Throwable _
        {}))))

(defn- recipe-widget-spec [kind]
  (get @recipe-widget-specs kind))

(defn build-preview-spec
  "Build a native node spec for a single sub-view, positioned relative to
   the 134×134 preview \"area\" origin (i.e. x/y are area-local, caller adds
   the area's own absolute offset when placing this as a child)."
  [view id]
  (case (:type view)
    :icon
    {:kind :image :props {:id id :x 0.0 :y 0.0 :w 134.0 :h 134.0 :src (:texture view)}}

    :recipe
    (if-let [widget-spec (recipe-widget-spec (:recipe-kind view))]
      ;; Use the full machine-GUI widget tree from tutorial_windows.xml
      ;; (background image + slots + progress bar), matching upstream CGUI.
      (assoc widget-spec :id id)
      ;; Fallback: simple background image if the widget spec isn't loaded
      (let [kind (:recipe-kind view)
            [x y w h scale] (get recipe-geom kind [0.0 0.0 134.0 134.0 1.0])]
        {:kind :image :props {:id id :x x :y y :w w :h h :scale scale
                               :src (get recipe-bg kind)}}))

    :crafting-grid
    {:kind :image :props {:id id :x 10.0 :y 5.0 :w 114.0 :h 114.0 :src crafting-grid-bg}}

    (:block-3d :item-3d)
    ;; Render the block/item as a scaled ItemStack in the preview area.
    ;; Upstream AcademyCraft renders these with GL perspective projection +
    ;; auto-rotation; we render at the full 134x134 area size with the item
    ;; model via GuiGraphics.renderFakeItem.
    (let [item-id (or (:item-id view) (:block-id view))]
      {:kind :preview-item :props {:id id :x 0.0 :y 0.0 :w 134.0 :h 134.0
                                    :item-id (str item-id)}})

    {:kind :box :props {:id id :x 0.0 :y 0.0 :w 134.0 :h 134.0 :fill 0x00000000}}))

;; ============================================================================
;; Navigation state — two-level (ViewGroup × SubView), reused verbatim
;; ============================================================================

(defn create-preview-state [tut-id]
  (let [groups (build-view-groups tut-id)]
    (atom {:view-groups groups :group-index 0 :sub-index 0})))

(defn current-view-group [state*]
  (let [{:keys [view-groups group-index]} @state*
        idx (or group-index 0)
        i (max 0 (min (dec (max 1 (count view-groups))) idx))]
    (when (seq view-groups)
      (nth view-groups i nil))))

(defn current-sub-view [state*]
  (when-let [vg (current-view-group state*)]
    (let [{:keys [sub-views sub-index]} vg
          svs (or sub-views [])
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
