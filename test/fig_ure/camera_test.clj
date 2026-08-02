(ns fig-ure.camera-test
  (:require [babashka.fs :as fs]
            [clojure.java.shell :as sh]
            [clojure.test :refer [deftest is testing]]
            [fig-ure.camera :as camera]
            [fig-ure.schema :as schema]
            [matcher-combinators.test :refer [match?]]))

(deftest format-snapshot-reading-test
  (testing "formats file path into a Malli-compliant SensorReading telemetry map"
    (let [reading (camera/format-snapshot-reading "/data/snapshots/snapshot-123.jpg")]
      (is (schema/valid? schema/SensorReading reading))
      (is (match? {:sensor/id    :camera/snapshot
                   :sensor/value "/data/snapshots/snapshot-123.jpg"
                   :sensor/unit  :file}
                  reading)))))

(deftest ensure-snapshots-dir!-test
  (testing "creates temporary snapshot directory if it does not exist"
    (let [temp-dir (str (fs/create-temp-dir {:prefix "fig-test-snapshots-"}))]
      (try
        (let [created-path (camera/ensure-snapshots-dir! temp-dir)]
          (is (fs/exists? created-path))
          (is (fs/directory? created-path)))
        (finally
          (fs/delete-tree temp-dir))))))

(deftest take-snapshot!-test
  (testing "captures snapshot successfully when camera command succeeds and creates file"
    (let [temp-dir (str (fs/create-temp-dir {:prefix "fig-test-take-"}))]
      (try
        (with-redefs [sh/sh (fn [_cmd & args]
                              ;; Find the output path argument after "-o"
                              (let [o-idx (.indexOf args "-o")
                                    out-file (nth args (inc o-idx))]
                                (spit out-file "fake-jpg-binary-content")
                                {:exit 0 :out "" :err ""}))]
          (let [res (camera/take-snapshot! temp-dir)]
            (is (match? {:status :ok} (select-keys res [:status])))
            (is (fs/exists? (:file-path res)))
            (is (number? (:timestamp res)))))
        (finally
          (fs/delete-tree temp-dir)))))

  (testing "returns error status when camera command fails (e.g. timeout)"
    (let [temp-dir (str (fs/create-temp-dir {:prefix "fig-test-fail-"}))]
      (try
        (with-redefs [sh/sh (fn [& _] {:exit 1 :out "" :err "Device timeout detected"})]
          (is (match? {:status        :error
                       :error/reason  :camera-capture-failed
                       :error/message "Device timeout detected"}
                      (camera/take-snapshot! temp-dir))))
        (finally
          (fs/delete-tree temp-dir)))))

  (testing "handles missing camera binary exception gracefully"
    (let [temp-dir (str (fs/create-temp-dir {:prefix "fig-test-missing-"}))]
      (try
        (with-redefs [sh/sh (fn [& _] (throw (java.io.IOException. "Cannot run program \"rpicam-still\": Exec failed")))]
          (is (thrown? java.io.IOException
                       (camera/take-snapshot! temp-dir))))
        (finally
          (fs/delete-tree temp-dir))))))
