(ns fig-ure.telemetry-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [fig-ure.sensors :as sensors]
            [fig-ure.stream :as stream]
            [fig-ure.telemetry :as telemetry]))

(deftest start-generic-producer-exception-resilience-test
  (testing "producer catches RuntimeException and emits :producer-error event without crashing worker loop"
    (let [out-chan  (async/chan 10)
          stop-chan (async/chan)
          counter   (atom 0)
          _         (#'telemetry/start-generic-producer!
                     "Failing Hardware" out-chan stop-chan 20
                     (fn []
                       (swap! counter inc)
                       (throw (RuntimeException. "I2C Bus Hardware Failure")))
                     identity)]
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

(deftest start-generic-consumer-exception-resilience-test
  (testing "consumer handler exception is safely caught without terminating consumer loop"
    (let [data-chan (async/chan 10)
          stop-chan (async/chan)
          calls     (atom 0)
          _         (#'telemetry/start-generic-consumer!
                     "Test Consumer" stop-chan
                     (fn [val]
                       (swap! calls inc)
                       (when (= val :boom)
                         (throw (RuntimeException. "Handler crashed!"))))
                     data-chan)]
      (async/>!! data-chan :boom)
      (async/>!! data-chan :ok)
      (Thread/sleep 100)
      (async/close! stop-chan)
      (is (= 2 @calls) "Consumer processed next message after exception in handler"))))

(deftest telemetry-pipeline-resilience-test
  (testing "start-telemetry-pipeline handles crashing producers without blowing up async channels"
    (with-redefs [sensors/read-sensor-readings (fn [& _] (throw (RuntimeException. "Hardware bus error")))
                  stream/take-snapshot!        (fn [& _] (throw (RuntimeException. "Camera process error")))]
      (let [sys-config {:sensor-buf-size    5
                        :camera-buf-size    5
                        :sensor-interval-ms 50
                        :camera-interval-ms 50}
            failing-sensors {:i2c-bus "1" :calibration nil}
            state (#'telemetry/start-telemetry-pipeline! sys-config failing-sensors)]
        (is (= :ready (:status state)))
        (is (some? (:stop-chan state)))
        (Thread/sleep 150)
        (async/close! (:stop-chan state))))))
