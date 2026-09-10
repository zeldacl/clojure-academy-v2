(ns cn.li.ability.editor.label
  "Single shared canvas-label truncation function for both editors and for
   this module's own graph.clj (P6 of the node-editor/spell-composer UI
   refactor plan). Before this, three independent copies existed: node_
   editor_reactive.clj and spell_composer_reactive.clj each had their own
   private ui-label (identical pixel-measuring logic, copy-pasted), and
   graph.clj's clip-label used a THIRD, different strategy -- a fixed
   CHARACTER count (28), which under a proportional font has no fixed
   pixel width at all (a run of \"i\"/\"l\" and a run of \"M\"/\"W\" at the
   same character count are nowhere near the same width).

   Uses cn.li.mcmod.client.platform-bridge's font metric when a platform
   has installed one (a real client), and its own deterministic fallback
   otherwise (headless tests, JMH, datagen) -- ability-runtime already
   legitimately depends on mcmod (see ability-runtime/build.gradle's own
   comment), and platform-bridge is itself a neutral dynamic-var
   indirection, not a direct Minecraft API reference."
  (:require [clojure.string :as str]
            [cn.li.mcmod.client.platform-bridge :as bridge]))

(defn ellipsize
  "value, max-width (px) -> value's string form, shortened with a
   trailing \"...\" so its measured pixel width fits max-width. Values
   already inside the budget are returned unchanged (never padded)."
  [value max-width]
  (let [s (str (or value ""))
        measure (fn [text]
                  (double (or (bridge/font-width-optional text)
                              (* 4.8 (count text)))))]
    (if (or (str/blank? s) (<= (measure s) (double max-width)))
      s
      (let [suffix "..."]
        (loop [n (count s)]
          (let [candidate (str (subs s 0 n) suffix)]
            (cond
              (<= (measure candidate) (double max-width)) candidate
              (zero? n) suffix
              :else (recur (dec n)))))))))
