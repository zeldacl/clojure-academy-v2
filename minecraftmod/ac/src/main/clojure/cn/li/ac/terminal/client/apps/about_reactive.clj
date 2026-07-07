(ns cn.li.ac.terminal.client.apps.about-reactive
  "Reactive About app — tab switching via signal, scrollable text.
   Replaces find-widget + set-visible! + set-text-color! + on-left-click pattern."
  (:require [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.core :as ui]
            [cn.li.mcmod.ui.signal :as sig]
            [cn.li.mcmod.ui.events :as events]
            [cn.li.ac.terminal.client.apps.reactive-helpers :as h]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.util.log :as log]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- load-about-data []
  (try
    (let [path (io/resource "assets/my_mod/config/about.edn")]
      (edn/read-string (slurp path)))
    (catch Throwable e
      (log/warn "Failed to load about.edn" (ex-message e))
      {:credits {:header [] :staff [] :donators []}
       :donation {:links [] :text []}})))

(defn create-runtime []
  (let [r (h/load-app "guis/about.xml")
        about-data (load-about-data)
        ;; Tab state signal (:credits or :donate)
        tab (h/tab-signal r :credits)
        ;; Scroll offset
        scroll (h/scroll-signal r)]
    ;; Credit text content as signal
    (let [credits-text (sig/signal-o (str/join "\n" (get-in about-data [:credits :staff] [])))]
      (rt/put-user-signal! r :credits-text credits-text))
    ;; Donation text
    (let [donate-text (sig/signal-o (str/join "\n" (get-in about-data [:donation :text] [])))]
      (rt/put-user-signal! r :donate-text donate-text))
    ;; Tab switch handlers
    (events/on! r :btn_credits :left-click (fn [_rt _n _e] (sig/sset-o! tab :credits)))
    (events/on! r :btn_donate :left-click (fn [_rt _n _e] (sig/sset-o! tab :donate)))
    ;; Donation link click → open URL
    (doseq [[idx link] (map-indexed vector (get-in about-data [:donation :links] []))]
      (events/on! r (keyword (str "link-" idx)) :left-click
        (fn [_rt _n _e] (bridge/open-screen! :open-url {:url (:url link)}))))
    r))

(defn open! []
  (let [r (create-runtime)]
    (h/open-app! r "About")))
