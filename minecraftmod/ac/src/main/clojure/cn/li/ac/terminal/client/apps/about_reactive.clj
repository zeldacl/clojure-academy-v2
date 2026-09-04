(ns cn.li.ac.terminal.client.apps.about-reactive
  "AcademyCraft About application backed by Presentation Runtime.
   The content remains data-driven from about.edn; the old XML/reactive node
   tree is intentionally gone."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.gui.presentation-application :as application]
            [cn.li.mcmod.i18n :as i18n]
            [cn.li.mcmod.util.log :as log])
  (:import [java.awt Desktop Desktop$Action] [java.net URI]))

(def ^:private link-slot 2)

(defn- load-about-data []
  (try
    (some-> (io/resource (str "assets/" modid/MOD-ID "/config/about.edn"))
            slurp
            edn/read-string)
    (catch Throwable e
      (log/warn "Failed to load about.edn" (ex-message e))
      {:credits {:header [] :staff [] :donators []}
       :donation {:links [] :text []}})))

(defn- as-line [v]
  (cond
    (map? v) (update v :label #(or % (str (:text v))))
    (string? v) {:label v}
    :else {:label (str v)}))

(defn- credit-lines [{:keys [header staff donators]}]
  (mapv as-line
        (concat
          (map str header)
          ["" "Staff"]
          (mapcat (fn [[job names]]
                    (cons (str job ":") (map #(str "  " %) names))) staff)
          ["" "Donators"]
          (map str (shuffle donators))
          [""
           (or (i18n/translate (str "about." modid/MOD-ID ".donators_info"))
               "In no particular order")
           "Thank you for playing!"])))

(defn- donation-lines [{:keys [text links]}]
  (mapv as-line
        (concat
          (map str (take link-slot text))
          (map (fn [{:keys [text url]}]
                 {:label text :url url}) links)
          (map str (drop link-slot text)))))

(defn- initial-state []
  (let [data (load-about-data)]
    {:title "About"
     :tab :credits
     :lines (credit-lines (:credits data))
     :status "Credits"
     :scroll 0.0
     :button-left {:label "Credits" :visible? true :rgba 0xCC315A78}
     :button-right {:label "Donate" :visible? true :rgba 0xCC315A78}
     :about-data data}))

(defn- open-link! [url]
  (try
    (if (and (Desktop/isDesktopSupported)
             (.isSupported (Desktop/getDesktop) Desktop$Action/BROWSE))
      (.browse (Desktop/getDesktop) (URI. (str url)))
      (log/warn "Desktop URL browsing is unavailable:" url))
    (catch Throwable e
      (log/warn "Cannot open URL" url (ex-message e)))))

(defn- dispatch-action [data action state]
  (case action
    :application/left
    (assoc state :tab :credits :status "Credits"
           :lines (credit-lines (:credits data)) :scroll 0.0)

    :application/right
    (assoc state :tab :donate :status "Donate"
           :lines (donation-lines (:donation data)) :scroll 0.0)

    :application/activate
    (if-let [url (get-in state [:selected-item :url])]
      (do (open-link! url)
          (assoc state :status (str "Opened " url)))
      (assoc state :status (str (:status state) " - selected")))

    :application/scroll state
    state))

(defn open! []
  (let [state (initial-state)
        data (:about-data state)]
    (application/mount!
      "application/about"
      "About"
      (dissoc state :about-data)
      (fn [action current]
        (dispatch-action data action current))
      nil
      :screen
      :academy.app/about)))
