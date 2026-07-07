(ns cn.li.ac.terminal.client.apps.settings-reactive
  "Reactive Settings app — checkbox rows bound to boolean signals.
   Replaces find-widget + comp/set-texture! + events/on-left-click pattern."
  (:require [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.core :as ui]
            [cn.li.mcmod.ui.signal :as sig]
            [cn.li.ac.terminal.client.apps.reactive-helpers :as h]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.util.log :as log]))

;; Config property definitions (same as old settings.clj)
(def ^:private props
  [{:key :attack-player :category "generic" :domain :ability :sp-only? true}
   {:key :destroy-blocks :category "generic" :domain :ability :sp-only? true}
   {:key :heads-or-tails :category "generic" :domain :tutorial :sp-only? false}
   {:key :use-mouse-wheel :category "generic" :domain :gameplay :sp-only? false}])

(defn- read-config [key]
  false)

(defn- toggle-config! [key]
  (log/info "Toggle config" key))

(defn create-runtime []
  (let [r (h/load-app "guis/settings.xml")
        ;; Create boolean signals for each toggle
        toggle-signals (into {}
                         (map (fn [p]
                                [(:key p) (h/checkbox-signal r (keyword (str "btn-" (name (:key p)))))])
                              props))]
    ;; Set initial values from config
    (doseq [p props]
      (sig/sset-o! (toggle-signals (:key p)) (read-config (:key p))))
    ;; Attach toggle handlers (toggle! is per-prop)
    r))

(defn create-screen []
  (let [r (create-runtime)]
    r))

(defn open! []
  (let [r (create-screen)]
    (h/open-app! r "Settings")))
