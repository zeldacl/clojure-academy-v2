(ns cn.li.ac.ability.client.screens.preset-editor-reactive
  "Presentation Runtime host for the ability preset editor.
   Preset/skill data is produced by the AC ViewModel; the screen is artifact-backed and does not depend on a renderer implementation."
  (:require [cn.li.ac.ability.client.read-model :as read-model]
            [cn.li.ac.ability.client.api :as api]
            [cn.li.ac.ability.client.managed-screens :as managed-screens]
            [cn.li.ac.ability.client.screens.preset-editor :as editor]
            [cn.li.ac.gui.presentation-application :as application]
            [cn.li.mcmod.client.platform-bridge :as bridge]))

(defonce ^:private active-mounts (atom {}))

(defn- owner-for [player-uuid]
  (read-model/local-client-owner player-uuid "preset-editor"))

(defn- render-state [owner]
  (let [data (or (editor/build-preset-editor-render-data owner) {})
        slots (vec (:slots data))
        slot-items (mapv (fn [idx slot]
                           {:kind :slot
                            :slot-index idx
                            :label (str "Slot " (inc idx) ": " (or (:skill-name slot) "empty"))
                            :action-label "Choose"})
                         (range 4) slots)
        skill-items (mapv (fn [skill]
                            {:kind :skill
                             :skill-id (:skill-id skill)
                             :cat-id (:cat-id skill)
                             :ctrl-id (:ctrl-id skill)
                             :label (str "Available: " (or (:skill-name skill) "?"))
                             :action-label "Assign"})
                          (:available-skills data))]
    {:lines [{:label (str "Preset " (inc (or (:selected-preset data) 0)) " / 4")}
             {:label (str "Active preset: " (inc (or (:active-preset data) 0)))}]
     :items (vec (concat slot-items skill-items))
     :status "Choose a slot, then assign an available skill"
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
  (let [owner (owner-for player-uuid)
        selected-slot* (atom nil)
        mount* (atom nil)]
    (editor/open-screen! owner)
    (let [vm (application/mount!
               (str "application/preset-editor/" player-uuid)
               "Preset Editor"
               (render-state owner)
               (fn [action current]
                 (let [item (:selected-item current)
                       selected (int (or (:selected current) 0))
                       next-selected (case action
                                       :application/left (mod (dec selected) 4)
                                       :application/right (mod (inc selected) 4)
                                       selected)]
                   (cond
                     (= action :application/left)
                     (editor/on-preset-tab-click owner next-selected)

                     (= action :application/right)
                     (editor/on-preset-tab-click owner next-selected)

                     (and (= action :application/activate) (= :slot (:kind item)))
                     (reset! selected-slot* (:slot-index item))

                     (and (= action :application/activate) (= :skill (:kind item)))
                     (if-let [slot-idx @selected-slot*]
                       (api/req-set-preset-slot!
                         owner selected slot-idx (:cat-id item) (:ctrl-id item)
                         (fn [_]
                           (reset! selected-slot* nil)
                           (when-let [mounted @mount*]
                             ((:refresh! mounted) (render-state owner)))))
                       nil))
                   (render-state owner)))
               #(do (swap! active-mounts dissoc (str player-uuid))
                    (editor/close-screen! owner)))]
      (reset! mount* vm)
      (swap! active-mounts assoc (str player-uuid)
             {:mount (:mount vm) :owner owner :refresh! (:refresh! vm)})
      vm)))
(defn open-screen! [owner]
  (open! (nth (editor/editor-owner-key owner) 2)))

(defn on-close! [owner]
  (editor/close-screen! owner)
  nil)

(defn install-widget-factory! [] nil)
