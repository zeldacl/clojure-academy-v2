(ns cn.li.ac.terminal.client.apps.about-reactive
  "Complete reactive replacement for about.clj.
   Signal-driven tab switching + scroll + donation link handling."
  (:require [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.signal :as sig]
            [cn.li.mcmod.ui.events :as events]
            [cn.li.mcmod.ui.xml :as ui-xml]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ============================================================================
;; Data loading (preserved from old about.clj)
;; ============================================================================

(defn- load-about-data []
  (try (let [path (io/resource "assets/my_mod/config/about.edn")]
         (edn/read-string (slurp path)))
       (catch Throwable e (log/warn "Failed to load about.edn" (ex-message e))
         {:credits {:header [] :staff [] :donators [] :donators-info ""}
          :donation {:links [] :text []}})))

(defn- build-credit-lines [credits-data]
  "Build text lines from credits data (preserved from old about.clj)."
  (let [lines (atom [])]
    (doseq [line (:header credits-data)] (swap! lines conj {:text line :bold? true :size 14}))
    (doseq [[job names] (:staff credits-data)]
      (swap! lines conj {:text (str job ": " (str/join ", " names)) :size 12}))
    (swap! lines conj {:text "--- Donators ---" :bold? true :size 12})
    (swap! lines conj {:text (:donators-info credits-data "") :size 10})
    (doseq [name (:donators credits-data)] (swap! lines conj {:text name :size 10}))
    (swap! lines conj {:text "Thank you for playing!" :bold? true :size 14})
    @lines))

(defn- build-donate-lines [donation-data]
  (let [lines (atom [])]
    (doseq [line (:text donation-data)] (swap! lines conj {:text line :size 12}))
    @lines))

;; ============================================================================
;; Reactive UI
;; ============================================================================

(defn create-runtime []
  (let [r (rt/create-runtime)
        spec (ui-xml/load-spec (modid/namespaced-path "guis/new/about.xml"))
        _ (rt/build! r spec)
        about-data (load-about-data)
        ;; Tab state signal
        active-tab (sig/signal-o :credits)
        _ (rt/put-user-signal! r :active-tab active-tab)
        ;; Credit content lines
        credit-lines (build-credit-lines (:credits about-data))
        donate-lines (build-donate-lines (:donation about-data))
        ;; Content signal (changes with tab)
        content-sig (sig/signal-o (str/join "\n" (map :text credit-lines)))
        _ (rt/put-user-signal! r :content-text content-sig)
        ;; Scroll signal
        scroll (sig/signal-d 0.0)
        _ (rt/put-user-signal! r :scroll-offset scroll)]

    ;; Tab switching
    (events/on! r "btn_credits" :left-click
      (fn [_rt _n _e]
        (sig/sset-o! active-tab :credits)
        (sig/sset-o! content-sig (str/join "\n" (map :text credit-lines)))))
    (events/on! r "btn_donate" :left-click
      (fn [_rt _n _e]
        (sig/sset-o! active-tab :donate)
        (sig/sset-o! content-sig (str/join "\n" (map :text donate-lines)))))

    ;; Donation links — about.xml has no per-link elements (content gap predating
    ;; this reactive port, not a string/keyword id issue); guard so a non-empty
    ;; :links list in about.edn doesn't crash the whole screen on open. Links
    ;; are currently not clickable in-game until dedicated UI elements exist.
    (doseq [[idx link] (map-indexed vector (:links (:donation about-data)))]
      (when-let [_n (rt/node-by-id r (str "link-" idx))]
        (events/on! r (str "link-" idx) :left-click
          (fn [_rt _n _e]
            (try (.browse (java.awt.Desktop/getDesktop) (java.net.URI. (:url link)))
                 (catch Exception _ (log/warn "Cannot open URL" (:url link))))))))

    ;; Scroll
    (events/on! r "scroll_area" :mouse-scroll
      (fn [_rt _n evt]
        (sig/sset-d! scroll (max 0.0 (+ (sig/sget-d scroll) (* (:delta evt) 0.01))))))
    r))

(defn open! []
  (let [r (create-runtime)]
    (bridge/open-reactive-screen! r "About")))
