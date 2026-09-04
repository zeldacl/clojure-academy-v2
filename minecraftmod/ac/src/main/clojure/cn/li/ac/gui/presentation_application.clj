(ns cn.li.ac.gui.presentation-application
  "Application surface controller backed exclusively by Presentation Runtime.
   Application modules own state and action semantics; this namespace adapts
   their map callbacks to a compiled view artifact (default
   :academy.app/application; callers may pass about/freq/install/media/…)."
  (:require [cn.li.ac.gui.presentation :as presentation]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(def binding-ids {:title 0 :lines 1 :status 2 :scroll 3 :modal 4
                  :button-left 5 :button-right 6 :input 7})
(def action-ids {0 :application/left 1 :application/right
                 2 :application/activate 3 :application/delete})

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
                     (if (map? result) result current)))
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

