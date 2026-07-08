(ns cn.li.ac.terminal.client.apps.settings-reactive
  "Complete reactive replacement for settings.clj.
   Signal-driven checkbox rows + config persistence + key binding + scroll."
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
            [cn.li.mcmod.ui.core :as ui]
            [cn.li.mcmod.ui.signal :as sig]
            [cn.li.mcmod.ui.events :as events]
            [cn.li.mcmod.ui.xml :as ui-xml]))

;; ============================================================================
;; Config definitions (preserved from old settings.clj)
;; ============================================================================

(def ^:private props
  [{:key :attack-player    :category "generic" :get ability-config/attack-player-enabled?    :domain config-common/ability-domain   :sp-only? true}
   {:key :destroy-blocks   :category "generic" :get ability-config/destroy-blocks-enabled?   :domain config-common/ability-domain   :sp-only? true}
   {:key :heads-or-tails   :category "generic" :get tutorial-config/heads-or-tails-enabled?  :domain config-common/tutorial-domain  :sp-only? false}
   {:key :use-mouse-wheel  :category "generic" :get gameplay-config/use-mouse-wheel-enabled? :domain config-common/gameplay-domain  :sp-only? false}])

(defn- persist! [domain key value]
  (config-persist/persist-config-value! domain key value))

(defn- toggle-config! [p]
  (let [new-val (not ((:get p)))]
    (persist! (:domain p) (:key p) new-val)
    new-val))

;; ============================================================================
;; Reactive UI
;; ============================================================================

(defn- build-checkbox-signal [r p]
  "Create a signal for checkbox state, initialized from config."
  (let [s (sig/signal-o ((:get p)))]
    (rt/put-user-signal! r (:key p) s)
    {:signal s :prop p}))

(defn create-runtime []
  (let [r (rt/create-runtime)
        spec (ui-xml/load-spec (modid/namespaced-path "guis/new/settings.xml"))
        _ (rt/build! r spec)
        ;; Create signals for each config toggle
        checkbox-signals (mapv #(build-checkbox-signal r %) props)
        ;; Scroll signal
        scroll (sig/signal-d 0.0)
        _ (rt/put-user-signal! r :scroll scroll)
        ;; Key binding state
        recording? (atom false)
        current-key (atom "LMENU")]
    ;; Attach click handlers for each checkbox
    (doseq [cs checkbox-signals]
      (let [p (:prop cs) s (:signal cs)
            btn-id (keyword (str "btn-" (name (:key p))))]
        (events/on! r btn-id :left-click
          (fn [_rt _n _e]
            (sig/sset-o! s (toggle-config! p))))))
    ;; Restore defaults button
    (events/on! r :btn-restore :left-click
      (fn [_rt _n _e]
        (doseq [p props] (persist! (:domain p) (:key p) false))
        (doseq [cs checkbox-signals] (sig/sset-o! (:signal cs) false))
        (log/info "Settings restored defaults")))
    ;; Scroll handler
    (events/on! r :scrollbar :mouse-scroll
      (fn [_rt _n evt]
        (let [delta (:delta evt)]
          (sig/sset-d! scroll (max 0.0 (min 1.0 (+ (sig/sget-d scroll) (* delta 0.01))))))))
    r))

(defn open! []
  (let [r (create-runtime)]
    (bridge/open-reactive-screen! r "Settings")))
