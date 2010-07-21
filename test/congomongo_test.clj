(ns congomongo-test
  (:use clojure.test
        somnium.congomongo
        somnium.congomongo.config
        somnium.congomongo.util
        somnium.congomongo.coerce
        clojure.contrib.pprint)
  (:use [clojure.contrib.json :only (read-json json-str)]))

(deftest coercions
  (let [forms   [:clojure :mongo :json]
        input   {:a {:b "c" :d "e" :f ["a" "b" "c"] :g {:h ["i" "j"]}}}
        results (for [from forms
                      to   forms
                      :let [start (condp = from
                                        :clojure input
                                        :json    (json-str input)
                                        :mongo   (coerce input [:clojure :mongo]))
                            x (coerce start [from to])
                            y (coerce x [to from])]
                      :when (not= from to)]
                  (if (= from :json)
                    [(read-json start) (read-json y) from to]
                    [start y from to]))]
    (doseq [t results]
      (is (= (t 0) (t 1)) (str (t 2) " " (t 3))))))

(def test-db "congomongotestdb")
(defn setup! [] (mongo! :db test-db))
(defn teardown! [] (drop-database! test-db))

(defmacro with-mongo [& body]
  `(do
     (setup!)
     ~@body
     (teardown!)))

(deftest databases-test
  (with-mongo
    (let [test-db2 "congomongotestdb-part-deux"]
    
      (is (= test-db (.getName (@*mongo-config* :db)))
          "default DB exists")
      (set-database! test-db2)

      (is (= test-db2 (.getName (@*mongo-config* :db)))
          "changed DB exists")
      (drop-database! test-db2))))

(defn make-points! []
  (println "slow insert of 10000 points:")
  (time
   (doseq [x (range 100)
           y (range 100)]
     (insert! :points {:x x :y y}))))

(deftest slow-insert-and-fetch
  (with-mongo
    (make-points!)
    (is (= (* 100 100)) (fetch-count :points))
    (is (= (fetch-count :points
                        :where {:x 42}) 100))))

(deftest destroy
  (with-mongo
    (make-points!)
    (let [point-id (:_id (fetch-one :points))]
      (destroy! :points
                {:_id point-id})
      (is (= (fetch-count :points) (dec (* 100 100))))
      (is (= nil (fetch-one :points
                            :where {:_id point-id}))))))

(deftest update
  (with-mongo
    (make-points!)
    (let [point-id (:_id (fetch-one :points))]
      (update! :points
               {:_id point-id}
               {:x "suffusion of yellow"})
      (is (= (:x (fetch-one :points
                            :where {:_id point-id}))
             "suffusion of yellow")))))

;; ;; mass insert chokes on excessively large inserts
;; ;; will need to implement some sort of chunking algorithm

 (deftest mass-insert
   (with-mongo
     (println "mass insert of 10000 points")
     (time
      (mass-insert! :points
                    (for [x (range 100) y (range 100)]
                      {:x x
                       :y y
                       :z (* x y)})))
     (is (= (* 100 100)
            (fetch-count :points))
         "mass-insert okay")))

 (deftest basic-indexing
   (with-mongo
     (make-points!)
     (add-index! :points [:x])
     (is (some #(= (into {} (% "key")) {"x" 1})
               (get-indexes :points)))))

(defrecord Foo [a b])

(deftest can-insert-records-as-maps
  (with-mongo
    (insert! :foos (Foo. 1 2))
    (let [found (fetch-one :foos)]
      (are (= 1 (:a found))
           (= 2 (:b found))
           ))))

(deftest gridfs-insert-and-fetch
  (with-mongo
    (is (empty? (fetch-files :testfs)))
    (let [f (insert-file! :testfs (.getBytes "toasted")
                          :filename "muffin" :contentType "food/breakfast")]
      (is (= "muffin" (:filename f)))
      (is (= "food/breakfast" (:contentType f)))
      (is (= 7 (:length f)))
      (is (= nil (fetch-one-file :testfs :where {:filename "monkey"})))
      (is (= f (fetch-one-file :testfs :where {:filename "muffin"})))
      (is (= f (fetch-one-file :testfs :where {:contentType "food/breakfast"})))
      (is (= (list f) (fetch-files :testfs))))))
 
(deftest gridfs-destroy
  (with-mongo
    (insert-file! :testfs (.getBytes "banana") :filename "lunch")
    (destroy-file! :testfs {:filename "lunch"})
    (is (empty? (fetch-files :testfs)))))
 
(deftest gridfs-insert-with-metadata
  (with-mongo
    (let [f (insert-file! :testfs (.getBytes "nuts")
                          :metadata { :calories 50, :opinion "tasty"})]
      (is (= "tasty" (f :opinion)))
      (is (= f (fetch-one-file :testfs :where { :opinion "tasty" }))))))
 
(deftest gridfs-write-file-to
  (with-mongo
    (let [f (insert-file! :testfs (.getBytes "banana"))]
      (let [o (java.io.ByteArrayOutputStream.)]
        (write-file-to :testfs f o)
        (is (= "banana" (str o)))))))

(deftest test-server-eval
  (with-mongo
    (is (= (server-eval
            "
function ()
{
 function square (n)
 {
  return n*n;                           ;
  }
 return square (25);
 }
") 625))))
