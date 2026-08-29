(ns cn.li.ac.ability.client.screens.preset-editor-reactive
  "Presentation Runtime controller for the AC preset editor.
   AC owns preset/skill semantics and RPCs; the artifact owns carousel/list
   layout and input routing."
  (:require [cn.li.ac.ability.client.read-model :as read-model]
            [cn.li.ac.ability.client.api :as api]
            [cn.li.ac.ability.client.screens.preset-editor :as editor]
            [cn.li.ac.gui.presentation :as presentation]))

(defonce ^:private active-mounts (atom {}))

(defn- owner-for [player-uuid]
  (read-model/local-client-owner player-uuid "preset-editor"))

(defn- slot-label [idx slot]
  (str "Slot " (inc idx) ": " (or (:skill-name slot) "empty")))

(defn- preset-slots [data preset-index]
  (vec (or (get (:all-preset-slots data) preset-index)
           (:slots data)
           [])))

(defn- render-state
  ([owner] (render-state owner nil))
  ([owner selected-slot]
  (let [data (or (editor/build-preset-editor-render-data owner) {})
        selected-preset (int (or (:selected-preset data) 0))
        selected-slot selected-slot
        slots (vec (:slots data))
        slot-items (mapv (fn [idx slot]
                           {:kind :slot :slot-index idx
                            :label (slot-label idx slot)
                            :action-label (if (= idx selected-slot) "Selected" "Select")
                            :remove-label "Remove"
                            :skill-id (:skill-id slot)})
                         (range 4) slots)
        skill-items (mapv (fn [skill]
                            {:kind :skill :skill-id (:skill-id skill)
                             :cat-id (:cat-id skill) :ctrl-id (:ctrl-id skill)
                             :label (str "Available: " (or (:skill-name skill) "?"))
                             :action-label "Assign"})
                          (:available-skills data))]
    {:title "Preset Editor"
     :preset-label (str "Preset " (inc selected-preset) " / 4")
     :active-label (str "Active preset: " (inc (or (:active-preset data) 0)))
     :preset-tabs (mapv (fn [idx] {:index idx
                                    :label (str "Preset " (inc idx))
                                    :action-label (if (= idx selected-preset) "Selected" "Open")})
                        (range 4))
     :slots slot-items
     :available-skills skill-items
     :selected-slot (double (or selected-slot -1))
     :status (if (some? selected-slot)
               (str "Slot " (inc selected-slot) " selected; choose a learned skill")
               "Select a slot, then assign or remove a skill")
     :button-left "Previous"
     :button-right "Next"})))

(defn refresh-ui! [mount owner]
  (when-let [{:keys [present!]} (get @active-mounts mount)]
    (present!)))

(defn refresh-active-screen! [player-uuid]
  (when-let [{:keys [mount owner]} (get @active-mounts (str player-uuid))]
    (refresh-ui! mount owner)))

(defn create-runtime [owner]
  {:owner owner :state (atom (render-state owner nil))})

(defn open! [player-uuid]
  (let [owner (owner-for player-uuid)
        selected-slot* (atom nil)
        mount* (atom nil)
        present! (fn [owner]
                   (when-let [vm @mount*]
                     (presentation/present! vm (render-state owner @selected-slot*))))]
    (editor/open-screen! owner)
    (let [vm (presentation/mount-view!
               {:view-id :academy.app/preset-editor
                :host-kind :screen
                 :state (render-state owner @selected-slot*)
                :dispatch-action!
                (fn [action payload _current]
                  (let [item (:item payload)
                        data (or (editor/build-preset-editor-render-data owner) {})
                        selected (int (or (:selected-preset data) 0))
                        slot-index (or (:slot-index item) @selected-slot*)
                        refresh #(present! owner)]
                    (case action
                      :preset/previous
                      (do (editor/on-preset-tab-click owner (mod (dec selected) 4))
                          (reset! selected-slot* nil)
                          (render-state owner @selected-slot*))
                      :preset/next
                      (do (editor/on-preset-tab-click owner (mod (inc selected) 4))
                          (reset! selected-slot* nil)
                          (render-state owner @selected-slot*))
                      :preset/select-tab
                      (do (editor/on-preset-tab-click owner (int (:index item)))
                          (reset! selected-slot* nil)
                          (render-state owner @selected-slot*))
                      :preset/select-slot
                      (do (reset! selected-slot* (int slot-index))
                          (render-state owner @selected-slot*))
                      :preset/remove-slot
                      (if (some? slot-index)
                        (do (api/req-set-preset-slot! owner selected (int slot-index) nil nil
                                                     (fn [_] (reset! selected-slot* nil)
                                                               (refresh)))
                            (render-state owner @selected-slot*))
                        (render-state owner @selected-slot*))
                      :preset/assign
                      (if (and (some? slot-index) (:ctrl-id item))
                        (do (api/req-set-preset-slot! owner selected (int slot-index)
                                                     (:cat-id item) (:ctrl-id item)
                                                     (fn [_] (reset! selected-slot* nil)
                                                               (refresh)))
                            (render-state owner @selected-slot*))
                        (render-state owner @selected-slot*))
                      (render-state owner @selected-slot*))))
                :on-close #(do (swap! active-mounts dissoc (str player-uuid))
                               (editor/close-screen! owner))})]
      (reset! mount* vm)
      (swap! active-mounts assoc (str player-uuid)
             {:mount (:mount vm) :owner owner :selected-slot* selected-slot*
              :present! #(present! owner)})
      vm)))

(defn open-screen! [owner]
  (open! (nth (editor/editor-owner-key owner) 2)))

(defn on-close! [owner]
  (editor/close-screen! owner)
  nil)
