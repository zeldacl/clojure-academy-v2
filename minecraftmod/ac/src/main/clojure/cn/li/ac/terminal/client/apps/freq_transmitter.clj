(ns cn.li.ac.terminal.client.apps.freq-transmitter
  "CLIENT-ONLY: frequency transmitter — interactive version (WIP).
  Currently delegates to the original static help page while the full
  interactive FSM is being wired up with server-side ray-trace scanning."
  (:require [cn.li.ac.terminal.client.apps.freq :as freq]
            [cn.li.mcmod.util.log :as log]))

(defn open!
  "Open the frequency transmitter. Currently delegates to the original
  static help; interactive FSM is pending server-side handler wiring."
  [player]
  (log/info "Opening freq transmitter (delegated to static page)")
  (freq/open! player))
