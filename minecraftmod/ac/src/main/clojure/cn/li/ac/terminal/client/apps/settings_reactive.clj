(ns cn.li.ac.terminal.client.apps.settings-reactive
  "Presentation Runtime Settings application.

   AC owns typed config and keybinding semantics; Presentation owns the
   retained list/input lifecycle. No legacy Settings widget tree remains."
  (:require [cn.li.ac.ability.config :as ability-config]
            [cn.li.ac.config.common :as config-common]
            [cn.li.ac.config.gameplay :as gameplay-config]
            [cn.li.ac.tutorial.config :as tutorial-config]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.gui.presentation-application :as application]
            [cn.li.ac.terminal.client.apps.ui-customize-reactive :as ui-customize]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.config.registry :as config-reg]
            [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.framework.platform :as platform]
            [cn.li.mcmod.i18n :as i18n]))

(def ^:private props
  [{:key :attack-player :prop-id "attackPlayer" :get ability-config/attack-player-enabled? :domain config-common/ability-domain :default true}
   {:key :destroy-blocks :prop-id "destroyBlocks" :get ability-config/destroy-blocks-enabled? :domain config-common/ability-domain :default true}
   {:key :heads-or-tails :prop-id "headsOrTails" :get tutorial-config/heads-or-tails-enabled? :domain config-common/tutorial-domain :default false}
   {:key :use-mouse-wheel :prop-id "useMouseWheel" :get gameplay-config/use-mouse-wheel-enabled? :domain config-common/gameplay-domain :default false}])

(def ^:private key-rows
  [{:label "Ability 1" :source :settings :config-key :ability-key-0 :default -100}
   {:label "Ability 2" :source :settings :config-key :ability-key-1 :default -99}
   {:label "Ability 3" :source :settings :config-key :ability-key-2 :default 82}
   {:label "Ability 4" :source :settings :config-key :ability-key-3 :default 70}
   {:label "Cycle selection" :source :bridge :input-id :content/cycle-selection :default 67}
   {:label "Edit preset" :source :settings :config-key :edit-preset-key :default 78}
   {:label "Activate ability" :source :bridge :input-id :content/toggle-primary-state :default 86}
   {:label "Debug overlay" :source :bridge :input-id :content/toggle-debug-overlay :default 293}
   {:label "Open terminal" :source :bridge :input-id :content/toggle-terminal :default 342}])

(defn- bridge-call [op & args]
  (try (apply bridge/call-adapter op args) (catch Throwable _ nil)))

(defn- persist! [domain key value]
  (config-reg/set-config-value! domain key value)
  (when-let [fw-atom (fw/fw-atom)]
    (platform/call-adapter fw-atom :config-persist :persist! domain key value)))

(defn- prop-label [id]
  (or (i18n/translate (str "settings." modid/MOD-ID ".prop." id)) id))

(defn- current-key-code [{:keys [source input-id config-key default]}]
  (if (= source :settings)
    (try (gameplay-config/input-key config-key) (catch Throwable _ default))
    (or (bridge-call :keybind-get-key-code input-id) default)))

(defn- key-name [{:keys [source input-id] :as row}]
  (or (when (= source :bridge) (bridge-call :keybind-get-key-name input-id))
      (bridge-call :settings-key-name (current-key-code row))
      (str "KEY_" (current-key-code row))))

(defn- editable? [_row]
  (boolean (or (bridge-call :keybind-rebind-supported?) true)))

(defn- conflict? [{:keys [input-id]}]
  (boolean (and input-id (bridge-call :keybind-conflict? input-id))))

(defn- settings-items []
  (vec
    (concat
      (map (fn [{:keys [key prop-id get] :as p}]
             {:kind :property :id key :label (str (prop-label prop-id) ": " (if (get) "ON" "OFF"))
              :action-label "Toggle" :property p}) props)
      [{:kind :header :id :key-header :label "Key bindings" :action-label ""}]
      (map (fn [row]
             {:kind :key :id (or (:config-key row) (:input-id row))
              :label (str (:label row) ": " (key-name row)
                          (when (conflict? row) " [conflict]")
                          (when-not (editable? row) " [read-only]"))
              :action-label (if (editable? row) "Edit" "View")
              :key-row row}) key-rows)
      [{:kind :customize :id :customize :label "Customize HUD layout" :action-label "Open"}
       {:kind :reset :id :reset :label "Restore defaults" :action-label "Reset"}])))

(defn- line-items [items]
  (mapv #(select-keys % [:label]) items))

(defn- reset-all! []
  (doseq [{:keys [domain key default]} props]
    (persist! domain key default))
  (doseq [{:keys [source input-id config-key default] :as row} key-rows]
    (if (= source :settings)
      (persist! config-common/gameplay-domain config-key default)
      (when (editable? row)
        (bridge-call :keybind-set-key! input-id default)))))

(defn- selected-item [current]
  (or (:selected-item current)
      (nth (settings-items) (int (or (:selected current) 0)) nil)))

(defn open! []
  (let [items (settings-items)
        initial {:items items
                 :lines (line-items items)
                 :status "Select an item; Enter activates it"
                 :button-left {:label "Previous" :visible? true}
                 :button-right {:label "Next" :visible? true}
                 :selected 0}]
    (application/mount!
      "application/settings"
      "Settings"
      initial
      (fn [action current]
        (let [items* (settings-items)
              idx (int (or (:selected current) 0))
              next-idx (case action
                         :application/left (mod (dec idx) (count items*))
                         :application/right (mod (inc idx) (count items*))
                         idx)
              item (selected-item (assoc current :selected next-idx))
              editing (:editing-key current)]
          (cond
            (= action :input/key)
            (if editing
              (let [code (int (or (:key-code (:selected-item current))
                                   (:key-code current) -1))]
                (when (and (not= code 256) (:key-row editing))
                  (let [{:keys [source input-id config-key]} editing]
                    (if (= source :settings)
                      (persist! config-common/gameplay-domain config-key code)
                      (bridge-call :keybind-set-key! input-id code))))
                {:items (settings-items) :lines (line-items (settings-items))
                 :selected idx :status (if (= code 256) "Key edit cancelled" "Key binding updated")
                 :editing-key nil})
              current)

            (= action :settings/activate)
            (case (:kind item)
              :property (let [{:keys [domain key get]} (:property item)]
                          (persist! domain key (not (get)))
                          {:items (settings-items) :lines (line-items (settings-items))
                           :selected next-idx :status "Setting updated"})
              :key (if (editable? (:key-row item))
                     {:items items* :lines (line-items items*) :selected next-idx
                      :editing-key (:key-row item) :status "Press a key (Escape cancels)"}
                     {:items items* :lines (line-items items*) :selected next-idx :status "Binding is read-only"})
              :customize (do (ui-customize/open!) current)
              :reset (do (reset-all!) {:items (settings-items) :lines (line-items (settings-items))
                                       :selected next-idx :status "Defaults restored"})
              {:items items* :lines (line-items items*) :selected next-idx})

            :else
            {:items items* :lines (line-items items*) :selected next-idx
             :editing-key editing :status (str "Selected item " (inc next-idx))})))
      nil
      :screen
      :academy.app/settings)))