(ns cn.li.ability.editor.chrome
  "Pure panel geometry for the shared editor shell
   (academy/shared/editor_shell.edn). Returns named-panel scalars only --
   nesting, alignment, and every per-node coordinate stay in the consuming
   .ui.edn, same division of labor as preset_editor's :selector-w/:selector-h.

   This exists because LayoutKernel never treats :visible as space-reclaiming
   (PaintKernel/HitKernel read it; the measure/arrange pass in
   LayoutKernel.java does not) -- so a collapsed panel must be expressed as a
   zero width/height binding, not a :visible toggle. Two different .ui.edn
   screens (different design-width/design-height, different open-widths for
   their palette/inspector content) share this one function; the screen's own
   reactive controller assoc's the result into whatever :shell-* state keys
   its .ui.edn binds against.

   No content knowledge lives here (see verifyCoreNoSkillKnowledge)."
  )

(def header-h 16.0)
(def footer-h 16.0)
(def diagnostics-open-h 28.0)

(defn panel-geometry
  "{:design-width :design-height
    :palette-open? :palette-open-w
    :inspector-open? :inspector-open-w
    :diagnostic-count} ->
   {:header-h :footer-h :body-h :diagnostics-h :palette-w :stage-w :inspector-w}

   palette-w/inspector-w are 0.0 when their *-open? flag is false, regardless
   of *-open-w -- that is the only mechanism this shell has for reclaiming a
   collapsed panel's space. stage-w always absorbs whatever width the other
   two do not use, so palette-w + stage-w + inspector-w == design-width
   exactly. diagnostics-h is 0.0 whenever diagnostic-count is 0 so an empty
   diagnostics list does not sit there occupying a fixed band."
  [{:keys [design-width design-height
           palette-open? palette-open-w
           inspector-open? inspector-open-w
           diagnostic-count]}]
  (let [dw (double design-width)
        dh (double design-height)
        palette-w (if palette-open? (double (or palette-open-w 0.0)) 0.0)
        inspector-w (if inspector-open? (double (or inspector-open-w 0.0)) 0.0)
        diagnostics-h (if (pos? (long (or diagnostic-count 0))) diagnostics-open-h 0.0)
        body-h (max 0.0 (- dh header-h footer-h diagnostics-h))
        stage-w (max 0.0 (- dw palette-w inspector-w))]
    {:header-h header-h
     :footer-h footer-h
     :body-h body-h
     :diagnostics-h diagnostics-h
     :palette-w palette-w
     :stage-w stage-w
     :inspector-w inspector-w}))
