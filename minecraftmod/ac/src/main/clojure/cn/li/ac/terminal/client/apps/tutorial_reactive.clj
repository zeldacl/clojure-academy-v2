(ns cn.li.ac.terminal.client.apps.tutorial-reactive
  "Tutorial catalogue and markdown content on Presentation Runtime.

   Tutorial navigation, recipe expansion, tag paging and preview projection
   remain AC-owned state semantics; the artifact and input lifecycle are owned
   by Presentation Runtime."
  (:require [cn.li.ac.ability.util.uuid :as uuid]
            [clojure.string :as str]
            [cn.li.ac.gui.presentation-application :as application]
            [cn.li.ac.tutorial.client.preview-reactive :as preview]
            [cn.li.ac.tutorial.client.state :as client-state]
            [cn.li.ac.tutorial.content :as tut-content]
            [cn.li.ac.tutorial.markdown-renderer :as markdown]
            [cn.li.ac.tutorial.messages :as tut-msg]
            [cn.li.ac.tutorial.registry :as tut-registry]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.network.client :as net-client]))

(defn- tutorial-title [lang tutorial]
  (or (:title (tut-content/load-tutorial-content lang (:id tutorial)))
      (name (:id tutorial))))

(defn- markdown-lines [text misaka-id]
  (vec
    (mapcat (fn [segment]
              (if (= :image (:type segment))
                [{:label (str "[image] " (:texture-path segment))}]
                (map (fn [line] {:label line})
                     (str/split-lines (str (:text segment))))))
            (markdown/render-segments (or text "") misaka-id))))

(defn- active? [player-uuid tut]
  (or (:default-installed? tut)
      (and (client-state/ready?)
           (client-state/is-activated? player-uuid (:id tut)))))

(defn- request-sync! []
  (when-let [owner (runtime-hooks/default-client-owner)]
    (net-client/send-to-server owner (tut-msg/msg-id :tutorial/request-sync) {}
      (fn [response]
        (when response (client-state/apply-sync! response))))))

(defn- tutorial-items [entries player-uuid lang]
  (let [ordered (sort-by (fn [t] (if (active? player-uuid t) 0 1)) entries)]
    (mapv (fn [t]
            {:label (str (when-not (active? player-uuid t) "[locked] ")
                          (tutorial-title lang t))
             :action-label "Open"
             :tutorial-id (:id t)})
          ordered)))

(defn- current-content [ctx]
  (let [{:keys [lang player-uuid current-tut-id]} @ctx
        cd (or (tut-content/load-tutorial-content lang current-tut-id) {})
        misaka (client-state/get-misaka-id player-uuid)]
    {:brief-lines (markdown-lines (:brief cd) misaka)
     :content-lines (markdown-lines (:content cd) misaka)}))

(defn- current-preview [ctx]
  (let [pvs (:pvs @ctx)
        vg (preview/current-view-group pvs)
        view (preview/current-sub-view pvs)]
    {:tag-items (mapv (fn [[idx group]]
                        {:label (str (when (= idx (:group-index @pvs)) "> ")
                                      (or (:display-text group) ""))
                         :tag-index idx})
                      (map-indexed vector (or (:view-groups @pvs) [])))
     :preview-items (if view (preview/preview-items view) [])
     :tag-tooltip (or (some-> (:hovered-tag @ctx)
                              (nth (or (:view-groups @pvs) []) nil)
                              :display-text)
                         "")
     :button-left {:label "Previous" :visible? (pos? (count (or (:sub-views vg) [])))}
     :button-right {:label "Next" :visible? (pos? (count (or (:sub-views vg) [])))}}))

(defn- snapshot [ctx]
  (let [{:keys [entries player-uuid lang current-tut-id]} @ctx
        title (if current-tut-id
                (tutorial-title lang (tut-registry/tutorial-by-id current-tut-id))
                "Select a tutorial")]
    (merge {:title "MisakaCloud Terminal"
            :status title
            :tutorial-items (tutorial-items entries player-uuid lang)
            :selected 0}
           (current-content ctx)
           (current-preview ctx))))

(defn- select-tutorial! [ctx tut-id]
  (when-let [tut (tut-registry/tutorial-by-id tut-id)]
    (swap! ctx assoc :current-tut-id (:id tut)
           :pvs (atom (preview/create-preview-state (:id tut))))))

(defn- dispatch! [ctx action current]
  (let [selected-item (:selected-item current)]
    (case action
      :tutorial/select
      (select-tutorial! ctx (:tutorial-id selected-item))

      :tutorial/tag
      (when-let [idx (:tag-index selected-item)]
        (preview/switch-view-group! (:pvs @ctx) (int idx)))

      :tutorial/tag-hover
      (if (= :enter (:hover-event current))
        (swap! ctx assoc :hovered-tag (:tag-index selected-item))
        (swap! ctx assoc :hovered-tag nil))

      :tutorial/prev
      (preview/cycle-sub-view! (:pvs @ctx) :prev)

      :tutorial/next
      (preview/cycle-sub-view! (:pvs @ctx) :next)

      ;; Presentation's generic scroll action is intentionally state-free: the
      ;; runtime owns clip/scroll offset and only asks the controller for data.
      :input/scroll nil
      nil)
    (snapshot ctx)))

(defn open! [player]
  (let [player-uuid (uuid/player-uuid player)
        _ (client-state/ensure-client-state! player-uuid)
        _ (request-sync!)
        lang (tut-content/current-lang)
        entries (vec (tut-registry/all-tutorials))
        first-entry (first entries)
        ctx (atom {:player-uuid player-uuid
                   :lang lang
                   :entries entries
                   :current-tut-id (some-> first-entry :id)
                   :pvs (atom (preview/create-preview-state (or (some-> first-entry :id) :welcome)))})]
    (application/mount!
      (str "application/tutorial/" player-uuid)
      "MisakaCloud Terminal"
      (snapshot ctx)
      (fn [action current] (dispatch! ctx action current))
      #(bridge/close-screen!)
      :screen
      :academy.app/tutorial)))
