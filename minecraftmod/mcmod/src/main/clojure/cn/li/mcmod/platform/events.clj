(ns cn.li.mcmod.platform.events
  "Platform-neutral event bridge via Framework function map.

   Fire fn stored at [:platform :event-bus :fire!]."
  (:require [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.util.log :as log]))

(defn install-fire-event-fn!
  [fire-fn _label]
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :event-bus :fire!] fire-fn)) nil)

(defn available? [] (boolean (get-in @(fw/fw-atom) [:platform :event-bus :fire!])))
(defn current   [] (get-in @(fw/fw-atom) [:platform :event-bus :fire!]))
(defn call-with-runtime [fire-fn f] (f fire-fn))

(defn fire-event!
  "Post an event object to the platform event bus. No-op when not installed."
  [event]
  (when-let [fire (get-in @(fw/fw-atom) [:platform :event-bus :fire!])]
    (when event
      (try
        (fire event)
        (catch Exception e
          (log/warn "Event dispatch failed:" (ex-message e)))))))
