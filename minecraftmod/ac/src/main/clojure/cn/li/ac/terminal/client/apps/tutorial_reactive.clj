(ns cn.li.ac.terminal.client.apps.tutorial-reactive
  "Tutorial catalogue and markdown content on Presentation Runtime."
  (:require [cn.li.ac.ability.util.uuid :as uuid]
            [clojure.string :as str]
            [cn.li.ac.gui.presentation-application :as application]
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

(defn- tutorial-lines [lang tutorial player-uuid]
  (let [content (tut-content/load-tutorial-content lang (:id tutorial))
        text (or (:content content) "")
        segments (markdown/render-segments text (client-state/get-misaka-id player-uuid))]
    (vec
      (mapcat (fn [segment]
                (if (= :image (:type segment))
                  [{:label (str "[image] " (:texture-path segment))}]
                  (map (fn [line] {:label line})
                       (str/split-lines (str (:text segment))))))
              segments))))

(defn- request-sync! [player-uuid]
  (when-let [owner (runtime-hooks/default-client-owner)]
    (net-client/send-to-server owner (tut-msg/msg-id :tutorial/request-sync) {}
      (fn [response]
        (when response (client-state/apply-sync! response))))))

(defn open! [player]
  (let [player-uuid (uuid/player-uuid player)
        _ (client-state/ensure-client-state! player-uuid)
        _ (request-sync! player-uuid)
        lang (tut-content/current-lang)
        tutorials (vec (tut-registry/all-tutorials))
        initial (first tutorials)]
    (application/mount!
      (str "application/tutorial/" player-uuid)
      "MisakaCloud Terminal"
      {:lines (if initial (tutorial-lines lang initial player-uuid) [])
       :status (if initial (tutorial-title lang initial) "No tutorials")
       :selected 0
       :button-left {:label "Previous" :visible? true}
       :button-right {:label "Next" :visible? true}}
      (fn [action state]
        (let [idx (int (or (:selected state) 0))
              next-idx (case action
                         :application/left (mod (dec idx) (max 1 (count tutorials)))
                         :application/right (mod (inc idx) (max 1 (count tutorials)))
                         idx)
              selected (when (seq tutorials) (nth tutorials next-idx))]
          (if selected
            {:selected next-idx
             :lines (tutorial-lines lang selected player-uuid)
             :status (tutorial-title lang selected)}
            state)))
      #(bridge/close-screen!))))
