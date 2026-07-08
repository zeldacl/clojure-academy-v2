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
            [cn.li.mcmod.ui.core :as ui]
            [cn.li.mcmod.ui.signal :as sig]
            [cn.li.mcmod.ui.events :as events]
            [cn.li.mcmod.ui.xml :as ui-xml])
  (:import [cn.li.mcmod.ui.node INode]))

(def ^:private check-tex-true
  (modid/asset-path "textures/guis" "check_true.png"))

(def ^:private check-tex-false
  (modid/asset-path "textures/guis" "check_false.png"))

(def ^:private props
  [{:key :attack-player  :category "generic" :get ability-config/attack-player-enabled?    :domain config-common/ability-domain  :sp-only? true}
   {:key :destroy-blocks :category "generic" :get ability-config/destroy-blocks-enabled?   :domain config-common/ability-domain  :sp-only? true}
   {:key :heads-or-tails :category "generic" :get tutorial-config/heads-or-tails-enabled?  :domain config-common/tutorial-domain :sp-only? false}
   {:key :use-mouse-wheel :category "generic" :get gameplay-config/use-mouse-wheel-enabled? :domain config-common/gameplay-domain :sp-only? false}])

(defn- persist! [domain key value] (config-persist/persist-config-value! domain key value))
(defn- toggle-config! [p] (let [v (not ((:get p)))] (persist! (:domain p) (:key p) v) v))

(defn- checkbox-text [p]
  (or (i18n/translate (str "ac.settings.prop." (name (:key p))))
      (name (:key p))))

(defn- update-checkbox-item! [r item p checked?]
  (let [text-n (ui/item-node item :text)
        box-n (ui/item-node item :box)]
    (ui/set-node-prop! r text-n :text (checkbox-text p))
    (ui/set-node-prop! r box-n :src (if checked? check-tex-true check-tex-false))))

(defn- wire-checkbox-click! [r item p]
  (let [^INode box (ui/item-node item :box)]
    (rt/register-event! r (.getIdx box) :left-click
      (fn [_ _ _]
        (let [new-val (toggle-config! p)]
          (update-checkbox-item! r item p new-val))))))

(defn- populate-settings-list! [r]
  (ui/list-set! r :settings-list props
    (fn [rt item p]
      (update-checkbox-item! rt item p ((:get p)))
      (wire-checkbox-click! rt item p))))

(defn create-runtime []
  (let [r (rt/create-runtime)
        _ (rt/build! r (ui-xml/load-spec (modid/namespaced-path "guis/new/settings.xml")))
        scroll (sig/signal-d 0.0)
        _ (rt/put-user-signal! r :scroll scroll)
        key-binding-text (sig/signal-o "LMENU")
        _ (rt/put-user-signal! r :key-binding-text key-binding-text)
        recording? (atom false)
        current-key (atom "LMENU")]
    (populate-settings-list! r)
    (ui/set-prop! r :key-label :text "Open Terminal")
    (ui/bind! r :key-value :text key-binding-text)
    (ui/set-prop! r :restore-label :text "Restore Defaults")
    (ui/set-prop! r :restore-button :text "Reset")
    (events/on! r :key-value :left-click
      (fn [_ _ _]
        (reset! recording? true)
        (sig/sset-o! key-binding-text "PRESS...")))
    (events/on! r :key-value :key
      (fn [_ _ evt]
        (when @recording?
          (reset! recording? false)
          (let [kn (or (:key-name evt) (str "KEY_" (:key-code evt)))]
            (reset! current-key kn)
            (sig/sset-o! key-binding-text kn)))))
    (events/on! r :restore-button :left-click
      (fn [_ _ _]
        (doseq [p props] (persist! (:domain p) (:key p) false))
        (populate-settings-list! r)
        (log/info "Settings restored defaults")))
    (events/on! r :scrollbar :mouse-scroll
      (fn [_ _ evt]
        (sig/sset-d! scroll (max 0.0 (min 1.0 (+ (sig/sget-d scroll) (* (:delta evt) 0.01)))))))
    r))

(defn open! []
  (let [r (create-runtime)]
    (bridge/open-reactive-screen! r "Settings")))
