(ns fig-ure.telemetry
  "Background worker for batching and pushing telemetry metrics to InfluxDB Cloud or local storage."
  (:require
   [clojure.core.async :as async]
   [fig-ure.sensors :as sensors]
   [fig-ure.stream :as stream]
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

(defn- start-generic-producer!
  [name out-chan stop-chan interval-ms produce-fn trans-fn]
  (async/go-loop []
    (let [[_val port] (async/alts! [stop-chan (async/timeout interval-ms)])]
      (if (= port stop-chan)
        (async/>! out-chan {:event/type    :producer-stopped
                            :producer/name name})
        (let [res (try
                    (produce-fn)
                    (catch Exception e
                      {:status        :error
                       :error/reason  :execution-failed
                       :error/message (.getMessage e)}))]
          (if (= (:status res) :ok)
            (async/>! out-chan {:event/type    :producer-data
                                :producer/name name
                                :data          (trans-fn res)})
            (async/>! out-chan {:event/type    :producer-error
                                :producer/name name
                                :error         res}))
          (recur))))))

(defn- start-generic-consumer!
  [name stop-chan handler-fn & chans]
  (let [listen-ports (conj (vec chans) stop-chan)]
    (async/go-loop []
      (let [[val port] (async/alts! listen-ports)]
        (if (= port stop-chan)
          (println (util/format-log-message name "Received stop signal. Exiting."))
          (do
            (try
              (handler-fn val)
              (catch Exception e
                (println (util/format-log-message name (str "Handler error: " (.getMessage e))))))
            (recur)))))))

(defn- handle-telemetry-event
  [val]
  (case (:event/type val)
    :producer-data    (println (util/format-log-message "Telemetry Consumer" (str "[" (:producer/name val) "] Data: " (:data val))))
    :producer-error   (println (util/format-log-message "Telemetry Consumer" (str "[" (:producer/name val) "] Error: " (get-in val [:error :error/message] "Read failed"))))
    :producer-stopped (println (util/format-log-message "Telemetry Consumer" (str "[" (:producer/name val) "] Stopped.")))
    (println (util/format-log-message "Telemetry Consumer" (str "Raw event: " val)))))

(defn- start-telemetry-consumer!
  [stop-chan & chans]
  (apply start-generic-consumer! "Telemetry Consumer" stop-chan handle-telemetry-event chans))

(defn- start-telemetry-pipeline!
  [sys-config sensors-component]
  (let [stop-chan (async/chan)
        sensor-chan (create-sensor-chan (:sensor-buf-size sys-config))
        camera-chan (create-camera-chan (:camera-buf-size sys-config))
        bus (or (:i2c-bus sensors-component) "1")
        calib (:calibration sensors-component)
        bme280-produce #(sensors/read-sensor-readings :bme280 bus calib)
        seesaw-produce #(sensors/read-sensor-readings :seesaw bus)]

    (start-generic-producer! "BME280" sensor-chan stop-chan
                             (:sensor-interval-ms sys-config)
                             bme280-produce
                             :readings)

    (start-generic-producer! "Seesaw" sensor-chan stop-chan
                             (:sensor-interval-ms sys-config)
                             seesaw-produce
                             :readings)

    (start-generic-producer! "Camera" camera-chan stop-chan
                             (:camera-interval-ms sys-config)
                             stream/take-snapshot!
                             (fn [res] [(sensors/format-reading :camera-snapshot (:file-path res) :file)]))

    (start-telemetry-consumer! stop-chan sensor-chan camera-chan)

    {:status :ready
     :stop-chan stop-chan
     :camera-chan camera-chan
     :sensor-chan sensor-chan}))

(defmethod ig/init-key :fig-ure/telemetry [_ config]
  (println "Initializing telemtry pipeline..." config)
  (start-telemetry-pipeline! config (:sensors config)))

(defmethod ig/halt-key! :fig-ure/telemetry [_ state]
  (println "Halting telemetry worker..." state)
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
