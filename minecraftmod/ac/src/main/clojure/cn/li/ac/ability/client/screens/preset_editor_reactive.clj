(ns cn.li.ac.ability.client.screens.preset-editor-reactive
  "Presentation Runtime host for the ability preset editor.
   Preset/skill data is produced by the AC ViewModel; the screen has no XML or
   legacy UiRt dependency."
  (:require [cn.li.ac.ability.client.read-model :as read-model]
            [cn.li.ac.ability.client.managed-screens :as managed-screens]
            [cn.li.ac.ability.client.screens.preset-editor :as editor]
            [cn.li.ac.gui.presentation-application :as application]
            [cn.li.mcmod.client.platform-bridge :as bridge]))

(defonce ^:private active-mounts (atom {}))

(defn- owner-for [player-uuid]
  (read-model/local-client-owner player-uuid "preset-editor"))

(defn- render-state [owner]
  (let [data (or (editor/build-preset-editor-render-data owner) {})
        slots (map-indexed
                (fn [idx slot]
                  {:label (str "Slot " (inc idx) ": "
                               (or (:skill-name slot) "empty"))})
                (:slots data))]
    {:lines (vec (concat
                   [{:label (str "Preset " (inc (or (:selected-preset data) 0))
                                  " / 4")}
                    {:label (str "Active preset: "
                                  (inc (or (:active-preset data) 0)))}]
                   slots
                   (when (seq (:available-skills data))
                     [{:label "Available skills:"}])
                   (map (fn [{:keys [skill-name]}]
                          {:label (str "  " (or skill-name "?"))})
                        (:available-skills data))))
     :status "Left/right changes preset; synced skills are shown below"
     :selected (or (:selected-preset data) 0)
     :button-left {:label "Previous" :visible? true}
     :button-right {:label "Next" :visible? true}}))

(defn refresh-ui! [mount owner]
  (when-let [{:keys [refresh!]} (get @active-mounts mount)]
    (refresh! (render-state owner))))

(defn refresh-active-screen! [player-uuid]
  (when-let [{:keys [mount owner]} (get @active-mounts (str player-uuid))]
    (refresh-ui! mount owner)))

(defn create-runtime [owner]
  {:owner owner :state (atom (render-state owner))})

(defn open! [player-uuid]
  (let [owner (owner-for player-uuid)]
    (editor/open-screen! owner)
    (let [vm (application/mount!
               (str "application/preset-editor/" player-uuid)
               "Preset Editor"
               (render-state owner)
               (fn [action current]
                 (let [selected (int (or (:selected current) 0))
                       next-selected (case action
                                       :application/left (mod (dec selected) 4)
                                       :application/right (mod (inc selected) 4)
                                       selected)]
                   (when (#{:application/left :application/right} action)
                     (editor/on-preset-tab-click owner next-selected))
                   (assoc (render-state owner) :selected next-selected)))
               #(do (swap! active-mounts dissoc (str player-uuid))
                    (editor/close-screen! owner)))]
      (swap! active-mounts assoc (str player-uuid)
             {:mount (:mount vm) :owner owner :refresh! (:refresh! vm)})
      vm)))

(defn open-screen! [owner]
  (open! (nth (editor/editor-owner-key owner) 2)))

(defn on-close! [owner]
  (editor/close-screen! owner)
  nil)

(defn install-widget-factory! [] nil)
