(ns cn.li.ac.tutorial.client.notification
  "CLIENT-ONLY: Tutorial activation notification — shows a toast when
  a previously unactivated tutorial becomes available.

  Called from cn.li.ac.tutorial.client.state/apply-sync! when the server
  sync response contains newly activated tutorial ids.

  Matches upstream AcademyCraft NotifyUI behavior (notification popup
  with tutorial title when a condition-based tutorial unlocks)."
  (:require [cn.li.mcmod.util.log :as log]))

(defn show-activation-toasts!
  "Show toast notifications for a set of newly activated tutorial ids.
  Each toast displays the tutorial's title (loaded via content module)
  for 3 seconds.

  Args:
    new-tut-ids — set of keyword tutorial ids that were just activated

  Returns nil.  Failures are logged and swallowed — notification is
  best-effort; the tutorial list still updates correctly even if a
  toast fails to render."
  [new-tut-ids]
  (when (seq new-tut-ids)
    (try
      (let [load-content (requiring-resolve 'cn.li.ac.tutorial.content/load-tutorial-content)
            show-toast!   (requiring-resolve 'cn.li.ac.client.toast/show-toast!)]
        (when (and load-content show-toast!)
          (doseq [tut-id new-tut-ids]
            (let [title (try
                          (:title (load-content (name tut-id)))
                          (catch Throwable _ (name tut-id)))]
              (show-toast! {:message-key "ac.gui.tutorial.updated"
                           :args [(or title (name tut-id))]
                           :duration-ms 3000})))))
      (catch Throwable _
        (log/debug "Failed to show tutorial activation toast"))))
  nil)
