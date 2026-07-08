(ns cn.li.ac.terminal.client.apps.tutorial-reactive
  "Complete reactive replacement for tutorial.clj — signal-driven page navigation."
  (:require [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.signal :as sig]
            [cn.li.mcmod.ui.events :as events]
            [cn.li.mcmod.ui.xml :as ui-xml]))

(defn create-runtime []
  (let [r (rt/create-runtime)
        spec (ui-xml/load-spec (modid/namespaced-path "guis/new/tutorial.xml"))
        _ (rt/build! r spec)
        current-page (sig/signal-l 0)
        total-pages (sig/signal-l 10)
        page-content (sig/signal-o "Loading tutorial...")]
    (rt/put-user-signal! r :current-page current-page)
    (rt/put-user-signal! r :total-pages total-pages)
    (rt/put-user-signal! r :page-content page-content)
    (events/on! r :btn-prev :left-click (fn [_ _ _] (sig/sset-l! current-page (max 0 (dec (sig/sget-l current-page))))))
    (events/on! r :btn-next :left-click (fn [_ _ _] (sig/sset-l! current-page (min (dec (sig/sget-l total-pages)) (inc (sig/sget-l current-page))))))
    r))

(defn open! [] (let [r (create-runtime)] (bridge/open-reactive-screen! r "Tutorial")))
