(ns fig-ure.camera
  "Low-level hardware driver for Raspberry Pi Camera Module 3 commands (rpicam-still, rpicam-vid)."
  (:require
   [babashka.fs :as fs]
   [clojure.java.shell :as sh]
   [fig-ure.domain :as domain]))

(set! *warn-on-reflection* true)

(def config
  {:snapshots-dir "data/snapshots"
   :cmd-rpicam "rpicam-still"
   :cmd-ffmpeg "ffmpeg"
   :timeout-ms "5000"})

(defn- format-timestamp []
  (let [time (java.time.Instant/now)
        formatter (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH-mm-ss")]
    (.format (.atZone time (java.time.ZoneId/systemDefault)) formatter)))

(defn- generate-snapshot-filename []
  (str "snapshot-" (format-timestamp) ".jpg"))

(defn ensure-snapshots-dir!
  "Ensures snapshots directory exists on disk."
  ([]
   (ensure-snapshots-dir! (:snapshots-dir config)))
  ([dir-path]
   (fs/create-dirs dir-path)
   (str (fs/absolutize dir-path))))

(defn format-snapshot-reading
  "Formats a camera snapshot file path into a Malli-compliant SensorReading telemetry map."
  [file-path]
  (domain/make-reading :camera/snapshot file-path :file))

(defn take-snapshot!
  "Executes rpicam-still hardware command to capture a still image snapshot to disk."
  ([] (take-snapshot! (:snapshots-dir config)))
  ([dir-path]
   (let [dir-abs   (ensure-snapshots-dir! dir-path)
         filename  (generate-snapshot-filename)
         file-path (str (fs/path dir-abs filename))
         res       (sh/sh (:cmd-rpicam config) "-t" "1000" "-o" file-path "--immediate")]
     (if (and (zero? (:exit res)) (fs/exists? file-path))
       {:status    :ok
        :file-path file-path
        :timestamp (System/currentTimeMillis)}
       {:status        :error
        :error/reason  :camera-capture-failed
        :error/message (:err res)}))))

(comment
  ;; Interactive REPL scratchpad
  (take-snapshot!)
  (ensure-snapshots-dir!))
