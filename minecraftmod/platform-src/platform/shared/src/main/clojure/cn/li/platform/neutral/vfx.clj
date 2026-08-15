(ns cn.li.platform.neutral.vfx
  "Opaque platform seam for the Minecraft-free VFX Frame ABI.

   This namespace deliberately knows only the client bridge and ABI map shape;
   it never requires the runtime implementation or AC namespaces.  Version
   backends provide Minecraft anchor snapshots and consume the returned
  `:stages`/`:payload` values.")

(defonce ^:private frame-sequence* (atom 0))
(defonce ^:private call-adapter*
  (delay (requiring-resolve
          'cn.li.platform.neutral.client-runtime/call-adapter)))

(defn- call-adapter [key & args]
  (apply @call-adapter* key args))

(defn- host []
  (call-adapter :vfx-host-api))

(defn installed? [] (boolean (host)))

(defn next-frame-id [] (swap! frame-sequence* inc))

(defn required-anchors []
  (when-let [api (host)] ((:required-anchors api))))

(defn tick!
  "Advance VFX exactly once for the supplied game tick."
  [context]
  (when-let [api (host)] ((:tick! api) context)))

(defn sample-frame! [context]
  (when-let [api (host)] ((:sample-frame! api) context)))

(defn frame-stage [frame-id stage]
  (when-let [api (host)] ((:frame-stage api) frame-id stage)))

(defn sample-stage!
  "Sample one immutable frame and return its stage batches.

   The caller must invoke `release-frame!` after every render stage that has
   consumed the frame.  A nil result means that AC is not installed yet."
  [context stage]
  (when-let [frame (sample-frame! context)]
    {:frame frame
     :frame-id (:frame-id frame)
     :batches (or (frame-stage (:frame-id frame) stage) [])}))

(defn release-frame! [frame-id]
  (when-let [api (host)] ((:release-frame! api) frame-id)))

(defn clear-world! [world-id]
  (when-let [api (host)] ((:clear-world! api) world-id)))

(defn reload-resources! [generation]
  (when-let [api (host)] ((:reload-resources! api) generation)))
