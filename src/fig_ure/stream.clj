(ns fig-ure.stream
  "Process lifecycle manager for local ffmpeg webcam ingestion, snapshots, and YouTube Live streaming."
  (:require [integrant.core :as ig]))

(defmethod ig/init-key :fig-ure/stream [_ config]
  (println "Initializing video stream manager..." config)
  {:status :ready})

(defmethod ig/halt-key! :fig-ure/stream [_ state]
  (println "Halting video stream manager..." state))

(comment
  ;; Interactive REPL scratchpad
  )
