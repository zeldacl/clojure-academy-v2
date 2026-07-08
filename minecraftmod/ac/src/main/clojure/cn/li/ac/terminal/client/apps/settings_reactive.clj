(ns cn.li.ac.terminal.client.apps.settings-reactive
  "Complete reactive replacement for settings.clj."
  (:require [cn.li.ac.ability.config :as ability-config]
            [cn.li.ac.config.common :as config-common]
            [cn.li.ac.config.gameplay :as gameplay-config]
            [cn.li.ac.tutorial.config :as tutorial-config]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.platform.config-persist :as config-persist]
            [cn.li.mcmod.i18n :as i18n]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.signal :as sig]
            [cn.li.mcmod.ui.events :as events]
            [cn.li.mcmod.ui.xml :as ui-xml]))

(def ^:private props
  [{:key :attack-player  :category "generic" :get ability-config/attack-player-enabled?    :domain config-common/ability-domain  :sp-only? true}
   {:key :destroy-blocks :category "generic" :get ability-config/destroy-blocks-enabled?   :domain config-common/ability-domain  :sp-only? true}
   {:key :heads-or-tails :category "generic" :get tutorial-config/heads-or-tails-enabled?  :domain config-common/tutorial-domain :sp-only? false}
   {:key :use-mouse-wheel :category "generic" :get gameplay-config/use-mouse-wheel-enabled? :domain config-common/gameplay-domain :sp-only? false}])

(defn- persist! [domain key value] (config-persist/persist-config-value! domain key value))
(defn- toggle-config! [p] (let [v (not ((:get p)))] (persist! (:domain p) (:key p) v) v))

(defn create-runtime []
  (let [r (rt/create-runtime)
        spec (ui-xml/load-spec (modid/namespaced-path "guis/new/settings.xml"))
        _ (rt/build! r spec)
        scroll (sig/signal-d 0.0)
        _ (rt/put-user-signal! r :scroll scroll)
        key-binding-text (sig/signal-o "LMENU")
        _ (rt/put-user-signal! r :key-binding-text key-binding-text)
        recording? (atom false)
        current-key (atom "LMENU")]
    ;; Checkbox toggles
    (doseq [p props]
      (let [s (sig/signal-o ((:get p)))
            btn-id (keyword (str "btn-" (name (:key p))))]
        (rt/put-user-signal! r (:key p) s)
        (events/on! r btn-id :left-click
          (fn [_ _ _] (sig/sset-o! s (toggle-config! p))))))
    ;; Restore defaults
    (events/on! r :btn-restore :left-click
      (fn [_ _ _]
        (doseq [p props] (persist! (:domain p) (:key p) false))
        (log/info "Settings restored defaults")))
    ;; Scroll
    (events/on! r :scrollbar :mouse-scroll
      (fn [_ _ evt] (sig/sset-d! scroll (max 0.0 (min 1.0 (+ (sig/sget-d scroll) (* (:delta evt) 0.01)))))))
    ;; Key binding recording
    (events/on! r :btn-key-bind :left-click
      (fn [_ _ _]
        (reset! recording? true)
        (sig/sset-o! key-binding-text "PRESS...")
        (events/on! r :root :key
          (fn [_ _ evt]
            (when @recording?
              (reset! recording? false)
              (let [kn (or (:key-name evt) (str "KEY_" (:key-code evt)))]
                (reset! current-key kn)
                (sig/sset-o! key-binding-text kn)))))))
    r))

(defn open! []
  (let [r (create-runtime)]
    (bridge/open-reactive-screen! r "Settings")))
