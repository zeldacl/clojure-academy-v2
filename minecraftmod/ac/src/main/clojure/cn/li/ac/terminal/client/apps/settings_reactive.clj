(ns cn.li.ac.terminal.client.apps.settings-reactive
  "Presentation Runtime Settings application.

   Configuration values remain server/client authoritative through the existing
   typed config registry; only the application presentation was replaced."
  (:require [cn.li.ac.ability.config :as ability-config]
            [cn.li.ac.config.common :as config-common]
            [cn.li.ac.config.gameplay :as gameplay-config]
            [cn.li.ac.tutorial.config :as tutorial-config]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.gui.presentation-application :as application]
            [cn.li.ac.terminal.client.apps.ui-customize-reactive :as ui-customize]
            [cn.li.mcmod.config.registry :as config-reg]
            [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.framework.platform :as platform]
            [cn.li.mcmod.i18n :as i18n]))

(def ^:private props
  [{:key :attack-player :prop-id "attackPlayer" :category "generic"
    :get ability-config/attack-player-enabled? :domain config-common/ability-domain}
   {:key :destroy-blocks :prop-id "destroyBlocks" :category "generic"
    :get ability-config/destroy-blocks-enabled? :domain config-common/ability-domain}
   {:key :heads-or-tails :prop-id "headsOrTails" :category "generic"
    :get tutorial-config/heads-or-tails-enabled? :domain config-common/tutorial-domain}
   {:key :use-mouse-wheel :prop-id "useMouseWheel" :category "generic"
    :get gameplay-config/use-mouse-wheel-enabled? :domain config-common/gameplay-domain}])

(def ^:private key-rows
  [{:label "Ability 1" :source :settings :config-key :ability-key-0 :default -100}
   {:label "Ability 2" :source :settings :config-key :ability-key-1 :default -99}
   {:label "Ability 3" :source :settings :config-key :ability-key-2 :default 82}
   {:label "Ability 4" :source :settings :config-key :ability-key-3 :default 70}
   {:label "Cycle selection" :source :bridge :input-id :content/cycle-selection}
   {:label "Edit preset" :source :settings :config-key :edit-preset-key}
   {:label "Activate ability" :source :bridge :input-id :content/toggle-primary-state}
   {:label "Debug overlay" :source :bridge :input-id :content/toggle-debug-overlay}
   {:label "Open terminal" :source :bridge :input-id :content/toggle-terminal}])

(defn- persist! [domain key value]
  (config-reg/set-config-value! domain key value)
  (when-let [fw-atom (fw/fw-atom)]
    (platform/call-adapter fw-atom :config-persist :persist! domain key value)))

(defn- prop-label [id]
  (or (i18n/translate (str "settings." modid/MOD-ID ".prop." id)) id))

(defn- initial-lines []
  (vec
    (concat
      (map (fn [{:keys [prop-id get]}]
             {:label (str (prop-label prop-id) ": " (if (get) "ON" "OFF"))}) props)
      [{:label ""} {:label "Key bindings"}]
      (map (fn [{:keys [label]}] {:label label}) key-rows)
      [{:label ""} {:label "Customize HUD layout"}])))

(defn open! []
  (application/mount!
    "application/settings"
    "Settings"
    {:lines (initial-lines)
     :status "Left/right selects; Enter toggles the selected setting"
     :button-left {:label "Previous" :visible? true}
     :button-right {:label "Next" :visible? true}
     :selected 0}
    (fn [action current]
      (let [idx (int (or (:selected current) 0))
            count* (+ (count props) (count key-rows) 3)
            next-idx (case action
                       :application/left (mod (dec idx) count*)
                       :application/right (mod (inc idx) count*)
                       idx)]
        (when (and (= action :application/activate)
                   (< next-idx (count props)))
          (let [{:keys [domain key get]} (nth props next-idx)]
            (persist! domain key (not (get)))))
        (when (and (= action :application/activate)
                   (= next-idx (+ (count props) (count key-rows) 2)))
          (ui-customize/open!))
        {:selected next-idx
         :lines (initial-lines)
         :status (str "Selected item " (inc next-idx))}))
    nil))
