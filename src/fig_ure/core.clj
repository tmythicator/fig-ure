(ns fig-ure.core
  "Main application entry point and Integrant system dependency graph configuration."
  (:gen-class)
  (:require
   [fig-ure.api]
   [fig-ure.camera]
   [fig-ure.sensors]
   [fig-ure.stream]
   [fig-ure.telemetry]
   [fig-ure.util :as util]
   [integrant.core :as ig]))

(def config
  {:fig-ure/sensors   {:i2c-bus "1"}

   :fig-ure/telemetry {:sensors            (ig/ref :fig-ure/sensors)
                       :sensor-interval-ms 5000
                       :sensor-buf-size    200
                       :influx/url    (System/getenv "INFLUX_URL")
                       :influx/token  (System/getenv "INFLUX_TOKEN")
                       :influx/bucket (or (System/getenv "INFLUX_BUCKET") "fig-ure")
                       :influx/org    (System/getenv "INFLUX_ORG")}

   :fig-ure/stream    {:timelapse-interval-ms 3600000 ;; 1 hour
                       :snapshots-dir         "data/snapshots"}

   :fig-ure/api       {:telemetry (ig/ref :fig-ure/telemetry)
                       :stream    (ig/ref :fig-ure/stream)
                       :port      3000}})

(defn -main
  "Application entry point for production deployment on Raspberry Pi 4B."
  [& _args]
  (util/log-message! "System" "Starting fig-ure edge node services...")
  (let [system (ig/init config)]
    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread. (fn []
                (util/log-message! "System" "Shutdown signal received. Halting system gracefully...")
                (ig/halt! system))))
    (util/log-message! "System" "fig-ure edge node system running smoothly.")))

(comment
  ;; REPL-driven experimentation block
  (def system (ig/init config))
  (ig/halt! system))
