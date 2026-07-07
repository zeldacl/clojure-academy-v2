(ns cn.li.ac.gui.reactive.register
  "Reactive GUI registration adapter — registers reactive screen-fns alongside old CGUI ones.
   Called from content_loader.clj during runtime content load."
  (:require [cn.li.mcmod.util.log :as log]))

(defn register-all!
  "Register reactive screen-fns for all migrated block GUIs.
   Currently stubbed — full registration requires updating gui-reg/screen-fn wiring.
   The reactive versions are ready at:
     ac/block/{solar,wind,imag,metal,node,matrix,interferer}/gui_reactive.clj"
  []
  (log/info "Reactive block GUI registration stub called (7 GUIs ready for activation)"))
