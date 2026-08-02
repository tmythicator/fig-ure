(ns fig-ure.telemetry-test
  (:require
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is testing]]
   [fig-ure.domain :as domain]
   [fig-ure.sensors :as sensors]
   [fig-ure.telemetry :as telemetry]
   [fig-ure.util :as util]))

(deftest start-sensor-producer-exception-resilience-test
  (testing "producer catches RuntimeException and emits :producer-error event without crashing worker loop"
    (let [out-chan  (async/chan 10)
          stop-chan (async/chan)
          counter   (atom 0)
          _         (#'telemetry/start-sensor-producer!
                     "Failing Hardware" out-chan stop-chan 20
                     (fn []
                       (swap! counter inc)
                       (throw (RuntimeException. "I2C Bus Hardware Failure"))))]
      (Thread/sleep 80)
      (async/close! stop-chan)
      (let [events (loop [acc []]
                     (if-let [v (async/<!! (async/go (async/alts! [out-chan (async/timeout 50)])))]
                       (if (= out-chan (second v))
                         (recur (conj acc (first v)))
                         acc)
                       acc))]
        (is (> @counter 1) "Producer loop continued executing despite exceptions")
        (is (some #(= :producer-error (:event/type %)) events) "Emitted error events into output channel")
        (is (some #(= "I2C Bus Hardware Failure" (get-in % [:error :error/message])) events))))))

(deftest start-telemetry-consumer-exception-resilience-test
  (testing "consumer handler exception is safely caught without terminating consumer loop"
    (let [data-chan (async/chan 10)
          stop-chan (async/chan)
          processed (atom [])]
      (with-redefs [util/log-telemetry-event!
                    (fn [_tag event]
                      (swap! processed conj (:data event))
                      (when (= (:data event) "bad-data")
                        (throw (RuntimeException. "Logging printer crashed!"))))]
        (#'telemetry/start-telemetry-consumer! stop-chan data-chan)
        (async/>!! data-chan (domain/make-telemetry-data-event "Test" "bad-data"))
        (async/>!! data-chan (domain/make-telemetry-data-event "Test" "good-data"))
        (Thread/sleep 100)
        (async/close! stop-chan)
        (is (= ["bad-data" "good-data"] @processed)
            "Consumer processed second message despite logging exception on first message")))))

(deftest telemetry-pipeline-resilience-test
  (testing "start-telemetry-pipeline handles crashing producers without blowing up async channels"
    (with-redefs [sensors/read-sensor-readings (fn [& _] (throw (RuntimeException. "Hardware bus error")))]
      (let [sys-config {:sensor-buf-size    5
                        :sensor-interval-ms 50}
            failing-sensors {:i2c-bus "1" :calibration nil}
            state (#'telemetry/start-telemetry-pipeline! sys-config failing-sensors)]
        (is (= :ready (:status state)))
        (is (some? (:stop-chan state)))
        (Thread/sleep 150)
        (async/close! (:stop-chan state))))))
