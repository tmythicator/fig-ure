(ns fig-ure.stream
  "Process lifecycle manager for local ffmpeg webcam ingestion, snapshots, and YouTube Live streaming."
  (:require
   [babashka.fs :as fs]
   [clojure.java.shell :as sh]
   [integrant.core :as ig]))

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

(defn take-snapshot!
  "Creates snapshot and places in into the configured folder."
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

(defmethod ig/init-key :fig-ure/stream [_ config]
  (println "Initializing video stream manager..." config)
  (ensure-snapshots-dir!)
  {:status :ready})

(defmethod ig/halt-key! :fig-ure/stream [_ state]
  (println "Halting video stream manager..." state))

(comment
  ;; Interactive REPL scratchpad
  (take-snapshot!)
  (ensure-snapshots-dir!))
