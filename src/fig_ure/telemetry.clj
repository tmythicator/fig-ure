(ns fig-ure.telemetry
  "Background worker for batching and pushing telemetry metrics to InfluxDB Cloud or local storage."
  (:require
   [clojure.core.async :as async]
   [fig-ure.camera :as camera]
   [fig-ure.domain :as domain]
   [fig-ure.sensors :as sensors]
   [fig-ure.util :as util]
   [integrant.core :as ig]))

(def config
  {:camera-interval-ms 600000 ;; 10 min
   :sensor-interval-ms 5000
   :camera-buf-size 10
   :sensor-buf-size 200})

(defn- create-camera-chan
  ([] (create-camera-chan (:camera-buf-size config)))
  ([buf-size]
   (async/chan (async/sliding-buffer buf-size))))

(defn- create-sensor-chan
  ([] (create-sensor-chan (:sensor-buf-size config)))
  ([buf-size]
   (async/chan (async/sliding-buffer buf-size))))

(defn- start-sensor-producer!
  [name out-chan stop-chan interval-ms produce-fn]
  (async/go-loop []
    (let [[_val port] (async/alts! [stop-chan (async/timeout interval-ms)])]
      (if (= port stop-chan)
        (async/>! out-chan (domain/make-telemetry-stopped-event name))
        (let [res (try
                    (produce-fn)
                    (catch Exception e
                      {:status        :error
                       :error/reason  :execution-failed
                       :error/message (.getMessage e)}))]
          (if (= (:status res) :ok)
            (async/>! out-chan (domain/make-telemetry-data-event name (:readings res)))
            (async/>! out-chan (domain/make-telemetry-error-event name res)))
          (recur))))))

(defn- start-camera-producer!
  [out-chan stop-chan interval-ms]
  (async/go-loop []
    (let [[_val port] (async/alts! [stop-chan (async/timeout interval-ms)])]
      (if (= port stop-chan)
        (async/>! out-chan (domain/make-telemetry-stopped-event "Camera"))
        (do
          (async/thread
            (let [res (try
                        (camera/take-snapshot!)
                        (catch Exception e
                          {:status        :error
                           :error/reason  :execution-failed
                           :error/message (.getMessage e)}))]
              (if (= (:status res) :ok)
                (async/>!! out-chan (domain/make-telemetry-data-event "Camera" [(camera/format-snapshot-reading (:file-path res))]))
                (async/>!! out-chan (domain/make-telemetry-error-event "Camera" res)))))
          (recur))))))

(defn- start-telemetry-consumer!
  [stop-chan sensor-chan]
  (async/go-loop []
    (let [[val port] (async/alts! [sensor-chan stop-chan])]
      (if (= port stop-chan)
        (util/log-message! "Telemetry Consumer" "Received stop signal. Exiting.")
        (do
          (try
            (util/log-telemetry-event! "Telemetry Consumer" val)
            (catch Exception e
              (util/log-message! "Telemetry Consumer" (str "Handler error: " (.getMessage e)))))
          (recur))))))

(defn- start-telemetry-pipeline!
  [sys-config sensors-component]
  (let [stop-chan (async/chan)
        sensor-chan (create-sensor-chan (:sensor-buf-size sys-config))
        camera-chan (create-camera-chan (:camera-buf-size sys-config))
        bus (or (:i2c-bus sensors-component) "1")
        calib (:calibration sensors-component)
        bme280-produce #(sensors/read-sensor-readings :bme280 bus calib)
        seesaw-produce #(sensors/read-sensor-readings :seesaw bus)]

    (start-sensor-producer! "BME280" sensor-chan stop-chan
                            (:sensor-interval-ms sys-config)
                            bme280-produce)

    (start-sensor-producer! "Seesaw" sensor-chan stop-chan
                            (:sensor-interval-ms sys-config)
                            seesaw-produce)

    (start-camera-producer! camera-chan stop-chan
                            (:camera-interval-ms sys-config))

    (start-telemetry-consumer! stop-chan sensor-chan)

    {:status :ready
     :stop-chan stop-chan
     :camera-chan camera-chan
     :sensor-chan sensor-chan}))

(defmethod ig/init-key :fig-ure/telemetry [_ config]
  (util/log-message! "Telemetry" (str "Initializing telemetry pipeline... " config))
  (start-telemetry-pipeline! config (:sensors config)))

(defmethod ig/halt-key! :fig-ure/telemetry [_ state]
  (util/log-message! "Telemetry" (str "Halting telemetry worker... " state))
  (when-let [stop-chan (:stop-chan state)]
    (async/close! stop-chan)))

(comment
  (def test-config
    {:sensor-buf-size    10
     :camera-buf-size    10
     :sensor-interval-ms 2000
     :camera-interval-ms 5000})

  (def system (ig/init-key :fig-ure/telemetry test-config))
  (ig/halt-key! :fig-ure/telemetry system))
