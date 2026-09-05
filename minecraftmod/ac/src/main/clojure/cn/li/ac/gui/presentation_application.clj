(ns cn.li.ac.gui.presentation-application
  "Application surface controller backed exclusively by Presentation Runtime.
   Application modules own state and action semantics; this namespace adapts
   their map callbacks to a compiled view artifact (default
   :academy.app/application; callers may pass about/freq/install/media/…)."
  (:require [clojure.string :as str]
            [cn.li.ac.gui.presentation :as presentation]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(def binding-ids {:title 0 :lines 1 :status 2 :scroll 3 :modal 4
                  :button-left 5 :button-right 6 :input 7})
(def action-ids {0 :application/left 1 :application/right
                 2 :application/activate 3 :application/delete})

(def ^:private text-draft-keys
  "Top-level view-state keys owned by text-inputs across application surfaces."
  [:input :edit-x :edit-y :edit-name :edit-desc :console-input])

(defn- live-text-edit?
  "True for glyph append / backspace / field :change — not submit/activate."
  [action payload]
  (or (= action :input/character)
      (= action :input/backspace)
      (and (map? payload)
           (or (true? (:backspace payload))
               (= 259 (int (or (:key-code payload) -1)))))
      (and (keyword? action)
           (let [n (name action)]
             (or (= n "input")
                 (= n "text-change")
                 (str/ends-with? n "change")
                 (str/ends-with? n "-input")
                 (str/includes? n "password"))))))

(defn- path-draft-key
  "Focus bind path [:state :k] / [:k] → :k."
  [payload]
  (let [path (:path payload)]
    (cond
      (and (vector? path) (= :state (first path)) (keyword? (second path)))
      (second path)
      (and (vector? path) (= 1 (count path)) (keyword? (first path)))
      (first path)
      :else nil)))

(defn- preserve-text-drafts
  "Keep runtime-edited text when an app handler returns a partial snapshot
   that omits the live draft (freq transmitter historically dropped :input)."
  [result current action payload]
  (let [result (if (map? result) result current)]
    (if-not (and (map? result) (map? current) (live-text-edit? action payload))
      result
      (let [k (path-draft-key payload)
            keys (cond-> (set text-draft-keys)
                   (keyword? k) (conj k))]
        (merge result (select-keys current keys))))))

(defn mount!
  ([owner title snapshot dispatch-action! on-close]
   (mount! owner title snapshot dispatch-action! on-close :screen))
  ([owner title snapshot dispatch-action! on-close host-kind]
   (mount! owner title snapshot dispatch-action! on-close host-kind :academy.app/application))
  ([owner title snapshot dispatch-action! on-close host-kind view-id]
   (let [state (merge {:title title :lines [] :status "" :scroll 0.0
                       :modal nil :input "" :items []}
                      snapshot)
         dispatch (fn [action payload current]
                   (let [current (cond-> (assoc current :selected-target (:target payload))
                                   (contains? payload :item)
                                   (assoc :selected-item (:item payload)
                                          :selected-index (:index payload))
                                   (contains? payload :key-code)
                                   (assoc :key-code (:key-code payload))
                                   (contains? payload :value)
                                   (assoc :value (:value payload)
                                          :progress-value (:value payload))
                                   (and (contains? payload :value)
                                        (or (= :input (path-draft-key payload))
                                            (= action :application/input)))
                                   (assoc :input (str (or (:value payload)
                                                          (:input current) "")))
                                   (contains? payload :progress)
                                   (assoc :progress (:progress payload))
                                   (contains? payload :drag?)
                                   (assoc :drag? (:drag? payload)))
                         current (cond-> current
                                   (contains? payload :hover-event)
                                   (assoc :hover-event (:hover-event payload)
                                          :hover? (:hover? payload)
                                          :previous-hover (:previous-hover payload)))
                         result (dispatch-action! action current)]
                     (preserve-text-drafts result current action payload)))
         vm (presentation/mount-view! {:view-id view-id
                             :host-kind host-kind
                             :state state
                             :dispatch-action! dispatch
                             :on-close on-close})]
     (when (= host-kind :screen)
       (client-bridge/call-adapter :presentation-open-screen!
                                    (:mount vm) title on-close))
     (assoc vm
            :owner owner
            :refresh! (fn
                        ([] (:state vm))
                        ([next-state] (presentation/present! vm (merge @(:state vm) next-state))))
            :state (:state vm)
            :model nil
            :host-kind host-kind))))

(defn unmount! [vm]
  (cond
    (map? vm) (presentation/unmount! vm)
    :else nil))
