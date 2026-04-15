 (ns cn.li.forge1201.client.ability-screen-bridge
   "CLIENT-ONLY screen bridge for ability GUIs (Forge layer)."
   (:require [cn.li.mcmod.platform.ability-lifecycle :as ability-runtime]
             [cn.li.mcmod.util.log :as log]
             [clojure.string :as str])
  (:import [net.minecraft.client.gui.screens Screen]
           [net.minecraft.client.gui GuiGraphics]
           [net.minecraft.client.gui Font]
           [net.minecraft.network.chat Component]
           [net.minecraft.client Minecraft]
           [net.minecraft.resources ResourceLocation]))


(defn- draw-string!
  [^GuiGraphics graphics ^String text x y color]
  (let [^Minecraft mc (Minecraft/getInstance)
        ^Font font (.-font mc)]
    (.drawString graphics font text (int x) (int y) (int color))))

(defn- normalize-texture-path
  [path]
  (when (and path (not (str/blank? path)))
    (cond
      (str/includes? path ":") path
      (str/starts-with? path "textures/") (str "my_mod:" path)
      :else (str "my_mod:textures/" path))))

;; ============================================================================
;; Skill Tree Screen
;; ============================================================================



(defn- normalize-skill-icon-path [icon]
  (normalize-texture-path icon))

(defn- render-skill-node
  "Render a single skill node."
  [^GuiGraphics graphics node]
  (let [{:keys [x y learned can-learn exp skill-icon]} node
        texture (or (normalize-skill-icon-path skill-icon)
                    (if learned
                      "my_mod:guis/skill_tree/skill_back"
                      "my_mod:guis/skill_tree/skill_outline"))
        color (cond
               learned 0x00FF00
               can-learn 0xFFFF00
               :else 0xFF0000)]
    ;; Render node icon or fallback
    (try
      (if-let [loc (ResourceLocation/tryParse texture)]
        (.blit graphics loc x y 0 0 20 20 20 20)
        (.fill graphics x y (+ x 20) (+ y 20) (bit-or 0xFF000000 color)))
      (catch Exception _e
        ;; Fallback: render colored square
        (.fill graphics x y (+ x 20) (+ y 20) (bit-or 0xFF000000 color))))

    ;; Render exp bar if learned
    (when (and learned (pos? exp))
      (let [bar-width (int (* 20 exp))]
        (.fill graphics x (+ y 22) (+ x bar-width) (+ y 24) 0xFF00FF00)))))

(defn- render-ability-info
  "Render ability info panel."
  [^GuiGraphics graphics info]
  (let [{:keys [category-name level cp overload can-level-up]} info]
    (draw-string! graphics (str category-name) 10 10 0xFFFFFF)
    (draw-string! graphics (str "Level: " level "/5") 10 25 0xFFFFFF)
    (draw-string! graphics (str "CP: " (:cur cp) "/" (:max cp)) 10 40 0xFFFFFF)
    (draw-string! graphics (str "Overload: " (:cur overload) "/" (:max overload)) 10 55 0xFFFFFF)

    ;; Render level-up button if available
    (when can-level-up
      (.fill graphics 10 200 90 220 0xFF00AA00)
      (draw-string! graphics "Level Up" 15 205 0xFFFFFF))))

(defn- render-skill-tooltip
  "Render tooltip for hovered skill."
  [^GuiGraphics graphics node mouse-x mouse-y]
  (when node
    (let [{:keys [skill-name skill-level can-learn conditions]} node
          tooltip-lines (vec
                         (concat
                           [(str skill-name " (Lv" skill-level ")")]
                           (when-not can-learn
                             (map #(str "- " (or (:description %) "Condition not met")) conditions))))]
      ;; Simple tooltip rendering
      (doseq [[idx line] (map-indexed vector tooltip-lines)]
        (draw-string! graphics (str line) (+ mouse-x 10) (+ mouse-y 10 (* idx 12)) 0xFFFFFF)))))

(defn- create-skill-tree-screen []
  (proxy [Screen] [(Component/literal "Skill Tree")]
    (render [^GuiGraphics graphics mouse-x mouse-y _partial-tick]
      (try
        ;; Get render data from AC layer
        (when-let [render-data (ability-runtime/client-build-skill-tree-render-data)]
          ;; Render background
          (let [^Screen screen this]
            (.renderBackground screen graphics))

          ;; Render ability info panel
          (when-let [info (:ability-info render-data)]
            (render-ability-info graphics info))

          ;; Render skill nodes
          (doseq [node (:skill-nodes render-data)]
            (when node
              (render-skill-node graphics node)))

          ;; Update hover state
          (ability-runtime/client-handle-skill-tree-hover! mouse-x mouse-y)

          ;; Render tooltip for hovered skill
          (when-let [hover-id (:hover-skill render-data)]
            (when-let [hovered-node (first (filter #(= (:skill-id %) hover-id)
                                                   (:skill-nodes render-data)))]
              (render-skill-tooltip graphics hovered-node mouse-x mouse-y))))
        (catch Exception e
          (log/error "Error rendering skill tree screen" e))))

    (keyPressed [^long key ^long scancode ^long modifiers]
      ;; 只处理ESC，其他按键返回false让MC继续处理
      (if (= key 256) ; GLFW_KEY_ESCAPE
        (let [^Minecraft mc (Minecraft/getInstance)]
          (.setScreen mc nil)
          true)
        false))

    (mouseClicked [mouse-x mouse-y _button]
      (try
        (boolean (ability-runtime/client-handle-skill-tree-click! mouse-x mouse-y))
        (catch Exception e
          (log/error "Error handling skill tree click" e)
          false)))

    (removed []
      (ability-runtime/client-close-skill-tree-screen!))))

;; ============================================================================
;; Preset Editor Screen
;; ============================================================================

(defn- render-preset-tab
  "Render a preset tab button."
  [^GuiGraphics graphics preset-idx selected? active? x y]
  (let [color (cond
               active? 0xFF00FF00
               selected? 0xFFFFFF00
               :else 0xFF808080)]
    (.fill graphics x y (+ x 40) (+ y 20) color)
    (draw-string! graphics (str "P" (inc preset-idx)) (+ x 10) (+ y 5) 0xFFFFFF)))

(defn- render-slot-assignment
  "Render a slot assignment."
  [^GuiGraphics graphics slot x y]
  (let [key-label (nth ["Z" "X" "C" "B"] (:idx slot))]
    (.fill graphics x y (+ x 100) (+ y 20) 0xFF404040)
    (draw-string! graphics key-label (+ x 5) (+ y 5) 0xFFFFFF)
    (when-let [skill-name (:skill-name slot)]
      (draw-string! graphics (str skill-name) (+ x 25) (+ y 5) 0xFFFFFF))))

(defn- render-available-skill
  "Render an available skill in the list."
  [^GuiGraphics graphics skill selected? x y]
  (let [color (if selected? 0xFF404080 0xFF202020)]
    (.fill graphics x y (+ x 150) (+ y 20) color)
    (draw-string! graphics (str (:skill-name skill)) (+ x 5) (+ y 5) 0xFFFFFF)))

(defn- create-preset-editor-screen []
  (proxy [Screen] [(Component/literal "Preset Editor")]
    (render [^GuiGraphics graphics mouse-x mouse-y _partial-tick]
      (try
        (when-let [render-data (ability-runtime/client-build-preset-editor-render-data)]
          ;; Render background
          (let [^Screen screen this]
            (.renderBackground screen graphics))

          ;; Render preset tabs
          (doseq [preset-idx (:presets render-data)]
            (render-preset-tab graphics preset-idx
                              (= preset-idx (:selected-preset render-data))
                              (= preset-idx (:active-preset render-data))
                              (+ 10 (* preset-idx 45)) 10))

          ;; Render slot assignments
          (doseq [[idx slot] (map-indexed vector (:slots render-data))]
            (when slot
              (render-slot-assignment graphics slot 10 (+ 40 (* idx 25)))))

          ;; Render available skills
          (draw-string! graphics "Available Skills:" 170 40 0xFFFFFF)
          (doseq [[idx skill] (map-indexed vector (:available-skills render-data))]
            (render-available-skill graphics skill
                                   (= (:skill-id skill) (:selected-skill render-data))
                                   170 (+ 60 (* idx 22))))

          ;; Render buttons
          (.fill graphics 10 200 90 220 0xFF00AA00)
          (draw-string! graphics "Save" 35 205 0xFFFFFF)

          (.fill graphics 100 200 180 220 0xFF0000AA)
          (draw-string! graphics "Set Active" 110 205 0xFFFFFF))
        (catch Exception e
          (log/error "Error rendering preset editor screen" e))))

    (mouseClicked [mouse-x mouse-y _button]
      (try
        (boolean (ability-runtime/client-handle-preset-editor-click! mouse-x mouse-y))
        (catch Exception e
          (log/error "Error handling preset editor click" e)
          false)))

    (removed []
      (ability-runtime/client-close-preset-editor-screen!))))

;; ============================================================================
;; Screen Opening Functions
;; ============================================================================

(defn open-skill-tree-screen!
  "Open skill tree screen. Called by AC layer via keybinds or developer GUI."
  ([player-uuid]
   (open-skill-tree-screen! player-uuid nil))
  ([player-uuid learn-context]
   (let [result (ability-runtime/client-open-skill-tree-screen! player-uuid learn-context)]
     (when (= (:command result) :open-screen)
       (let [^Minecraft mc (Minecraft/getInstance)]
         (.setScreen mc (create-skill-tree-screen)))))))

(defn open-preset-editor-screen!
  "Open preset editor screen. Called by AC layer via keybinds."
  [player-uuid]
  (let [result (ability-runtime/client-open-preset-editor-screen! player-uuid)]
    (when (= (:command result) :open-screen)
      (let [^Minecraft mc (Minecraft/getInstance)]
        (.setScreen mc (create-preset-editor-screen))))))

(defn init!
  "Initialize screen bridge."
  []
  (log/info "Ability screen bridge initialized"))
