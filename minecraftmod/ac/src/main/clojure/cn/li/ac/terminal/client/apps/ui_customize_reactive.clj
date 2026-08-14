(ns cn.li.ac.terminal.client.apps.ui-customize-reactive
  "Presentation Runtime HUD-position editor.

   Position data remains in the gameplay config registry; the Screen itself
   is now a typed application ViewModel rather than an XML/reactive tree."
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

(defn- lines []
  (mapv (fn [{:keys [id]}]
          (let [[x y] (gameplay/hud-position id)]
            {:label (format "%s: %.0f, %.0f" (name id) (double x) (double y))
             :id id}))
        elements))

(defn open! []
  (let [state {:title "Customize UI"
               :lines (lines)
               :status "Select an element with left/right, Enter to reset"
               :button-left {:label "Previous" :visible? true}
               :button-right {:label "Next" :visible? true}
               :selected 0}]
    (application/mount!
      "application/ui-customize"
      "Customize UI"
      state
      (fn [action current]
        (let [idx (int (or (:selected current) 0))
              next-idx (case action
                         :application/left (mod (dec idx) (count elements))
                         :application/right (mod (inc idx) (count elements))
                         idx)
              element (nth elements next-idx)]
          (when (= action :application/activate)
            (persist! (:config-key element) [0.0 0.0]))
          {:selected next-idx
           :lines (lines)
           :status (str "Selected " (name (:id element))) }))
      nil)))
