(ns cn.li.ac.terminal.client.apps.ui-customize-reactive
  "Presentation Runtime HUD-position editor.

   AC owns position validation and persistence; Presentation owns selection,
   text-input focus and retained rendering."
  (:require [cn.li.ac.config.common :as config-common]
            [cn.li.ac.config.gameplay :as gameplay]
            [cn.li.ac.gui.presentation-application :as application]
            [cn.li.mcmod.config.registry :as config-reg]
            [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.framework.platform :as platform]))

(def ^:private elements
  [{:id :cpbar :config-key :hud-cpbar-position}
   {:id :keyhint :config-key :hud-keyhint-position}
   {:id :media :config-key :hud-media-position}
   {:id :notification :config-key :hud-notification-position}])

(defn- persist! [config-key position]
  (config-reg/set-config-value! config-common/gameplay-domain config-key position)
  (when-let [fw-atom (fw/fw-atom)]
    (platform/call-adapter fw-atom :config-persist :persist!
                           config-common/gameplay-domain config-key position)))

(defn- position [element]
  (gameplay/hud-position (:id element)))

(defn- items []
  (mapv (fn [{:keys [id] :as element}]
          (let [[x y] (position element)]
            {:label (format "%s: %.0f, %.0f" (name id) (double x) (double y))
             :action-label "Select"
             :element-id id
             :element element}))
        elements))

(defn- preview-composite
  "HUD element markers at configured positions (main ui_edit preview parity)."
  []
  (vec
   (mapcat
    (fn [{:keys [id] :as element}]
      (let [[x y] (position element)
            x (double x)
            y (double y)
            label (name id)]
        [{:kind :quad :x x :y y :w 56.0 :h 14.0
          :rgba (unchecked-int 0x88315A78)}
         {:kind :text :text label :x (+ x 2.0) :y (+ y 2.0) :w 52.0 :h 10.0
          :font-size 8.0 :rgba (unchecked-int 0xFFFFFFFF)}]))
    elements)))

(defn- parse-coordinate [value]
  (try
    (let [n (Double/parseDouble (str value))]
      (when (and (Double/isFinite n) (<= -4096.0 n 4096.0)) n))
    (catch Throwable _ nil)))

(defn- snapshot [ctx current]
  (let [idx (int (or (:selected @ctx) 0))
        element (nth elements (max 0 (min (dec (count elements)) idx)))
        [x y] (position element)]
    {:title "Customize UI"
     :items (items)
     :selected idx
     :edit-x (or (:edit-x current) (str x))
     :edit-y (or (:edit-y current) (str y))
     :editbox-visible? true
     :preview-composite (preview-composite)
     :reset-label "Reset position"
     :status (str "Selected " (name (:id element)))}))

(defn open! []
  (let [ctx (atom {:selected 0})
        [x y] (position (first elements))
        initial {:title "Customize UI" :items (items) :selected 0
                 :edit-x (str x) :edit-y (str y)
                 :editbox-visible? true
                 :preview-composite (preview-composite)
                 :reset-label "Reset position"
                 :status "Select an element and edit X/Y"}]
    (application/mount!
      "application/ui-customize"
      "Customize UI"
      initial
      (fn [action current]
        (let [selected-item (:selected-item current)
              item-index (int (or (:selected-index current) (:selected @ctx) 0))
              selected (or (:element selected-item)
                           (nth elements (max 0 (min (dec (count elements)) item-index))))
              [old-x old-y] (position selected)
              x-value (parse-coordinate (:edit-x current))
              y-value (parse-coordinate (:edit-y current))]
          (case action
            :customize/select
            (do (reset! ctx {:selected item-index})
                (let [[sx sy] (position selected)]
                  {:title "Customize UI" :items (items) :selected item-index
                   :edit-x (str sx) :edit-y (str sy)
                   :editbox-visible? true
                   :preview-composite (preview-composite)
                   :reset-label "Reset position"
                   :status (str "Selected " (name (:id selected)))}))

            :customize/x-submit
            :customize/y-submit
            (let [x* (or x-value old-x) y* (or y-value old-y)]
              (persist! (:config-key selected) [x* y*])
              (snapshot ctx (assoc current :edit-x (str x*) :edit-y (str y*))))

            :customize/x-change
            :customize/y-change
            (snapshot ctx current)

            :customize/reset
            (do (persist! (:config-key selected) [0.0 0.0])
                (snapshot ctx (assoc current :edit-x "0.0" :edit-y "0.0")))

            (snapshot ctx current))))
      nil
      :screen
      :academy.app/ui-customize)))