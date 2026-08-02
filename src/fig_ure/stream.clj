(ns fig-ure.stream
  "High-level process lifecycle manager for camera timelapse scheduling and video streaming."
  (:require
   [clojure.core.async :as async]
   [fig-ure.camera :as camera]
   [fig-ure.util :as util]
   [integrant.core :as ig]))

(set! *warn-on-reflection* true)

(def config
  {:timelapse-interval-ms 3600000 ;; 1 hour
   :auto-timelapse? true})

(defn- start-timelapse-loop!
  "Background worker loop for periodic timelapse snapshots."
  [stop-chan interval-ms]
  (async/go-loop []
    (let [[_val port] (async/alts! [stop-chan (async/timeout interval-ms)])]
      (if (= port stop-chan)
        (util/log-message! "Stream Manager" "Stopped periodic timelapse worker.")
        (do
          (async/thread
            (let [res (try
                        (camera/take-snapshot!)
                        (catch Exception e
                          {:status        :error
                           :error/reason  :execution-failed
                           :error/message (.getMessage e)}))]
              (if (= (:status res) :ok)
                (util/log-message! "Stream Manager" (str "Timelapse snapshot saved: " (:file-path res)))
                (util/log-message! "Stream Manager" (str "Timelapse snapshot failed: " (get-in res [:error :error/message]))))))
          (recur))))))

(defmethod ig/init-key :fig-ure/stream [_ sys-config]
  (let [opts      (merge config sys-config)
        stop-chan (async/chan)]
    (util/log-message! "Stream Manager" (str "Initializing media stream manager with config: " opts))
    (camera/ensure-snapshots-dir!)
    (when (:auto-timelapse? opts)
      (start-timelapse-loop! stop-chan (:timelapse-interval-ms opts)))
    {:status    :ready
     :stop-chan stop-chan}))

(defmethod ig/halt-key! :fig-ure/stream [_ state]
  (util/log-message! "Stream Manager" "Halting stream manager...")
  (when-let [stop-chan (:stop-chan state)]
    (async/close! stop-chan)))
