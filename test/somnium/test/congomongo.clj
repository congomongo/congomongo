(ns somnium.test.congomongo
  (:use clojure.test
        somnium.congomongo
        somnium.congomongo.config
        somnium.congomongo.coerce
        clojure.pprint)
  (:use [clojure.data.json :only (read-json json-str)])
  (:import [com.mongodb BasicDBObject BasicDBObjectBuilder]))

(deftest coercions
  (let [clojure      {:a {:b "c" :d 1 :f ["a" "b" "c"] :g {:h ["i" "j" -42.42]}}}
        mongo        (.. (BasicDBObjectBuilder/start)
                         (push "a")
                         (add "b" "c")
                         (add "d" 1)
                         (add "f" ["a" "b" "c"])
                         (push "g")
                         (add "h" ["i" "j" -42.42])
                         get)
        clojure-json (json-str clojure) ; no padding
        mongo-json   (str mongo)        ; contains whitespace padding
        from    {:clojure clojure
                 :mongo   mongo
                 :json    clojure-json}
        to      (assoc from nil nil)]
    (doseq [[from original] from, [to expected] to
            :let [actual   (coerce original [from to])
                  expected (if (= [from to] [:mongo :json]) ; padding diff
                             mongo-json
                             expected)]]
      (is (= actual expected) [from to]))))

;; MongoLab test setup courtesy of World Singles, intended for Travis
;; CI testing...

;; MongoLab Free test DB: ds029317.mongolab.com
(def test-db-host (get (System/getenv) "MONGOHOST" "127.0.0.1"))
;; MongoLab Free test DB: 29317
(def test-db-port (Integer/parseInt (get (System/getenv) "MONGOPORT" "27017")))
;; MongoLab Free test DB: congomongo/mongocongo
(def test-db-user (get (System/getenv) "MONGOUSER" nil))
(def test-db-pass (get (System/getenv) "MONGOPASS" nil))
(def test-db "congomongotestdb")
(defn- drop-test-collections!
  "When we can't drop the test database (because it requires admin rights),
   we just drop all the non-system connections."
  []
  (doseq [^String coll (collections)]
    (when-not (.startsWith coll "system")
      (drop-coll! coll))))
(defn setup! []
  (mongo! :db test-db :host test-db-host :port test-db-port)
  (when (and test-db-user test-db-pass)
    (authenticate test-db-user test-db-pass)
    (drop-test-collections!)))
(defn teardown! []
  (if (and test-db-user test-db-pass)
    (try ; some tests don't authenticate so ignore failures here:
      (drop-test-collections!)
      (catch Exception _))
    (drop-database! test-db)))

(defmacro with-test-mongo [& body]
  `(do
     (setup!)
     ~@body
     (teardown!)))

(deftest options-on-connections
  (with-test-mongo
    ;; set some non-default option values
    (let [a (make-connection "congomongotest-db-a" :host test-db-host :port test-db-port (mongo-options :auto-connect-retry true :w 1 :safe true))
          m (:mongo a)
          opts (.getMongoOptions m)]
      ;; check non-default options attached to Mongo object
      (is (.autoConnectRetry opts))
      (is (.safe opts))
      (is (= 1 (.w opts)))
      ;; check a default option as well
      (is (not (.slaveOk opts))))))

(deftest with-mongo-database
  (with-test-mongo
    (let [a (make-connection "congomongotest-db-a" :host test-db-host :port test-db-port)]
      (with-mongo a
        (with-db "congomongotest-db-b"
          (testing "with-mongo uses new database"
                   (is (= "congomongotest-db-b" (.getName (*mongo-config* :db))))))
        (testing "with-mongo uses connection db "
                 (is (= "congomongotest-db-a" (.getName (*mongo-config* :db)))))))))

(deftest with-mongo-interactions
  (with-test-mongo
    (let [a (make-connection "congomongotest-db-a" :host test-db-host :port test-db-port)
          b (make-connection "congomongotest-db-b" :host test-db-host :port test-db-port)]
      (with-mongo a
        (testing "with-mongo sets the mongo-config"
          (is (= "congomongotest-db-a" (.getName (*mongo-config* :db)))))
        (testing "mongo! inside with-mongo stomps on current config"
          (mongo! :db "congomongotest-db-b" :host test-db-host :port test-db-port)
          (is (= "congomongotest-db-b" (.getName (*mongo-config* :db))))))
      (testing "and previous mongo! inside with-mongo is visible afterwards"
        (is (= "congomongotest-db-b" (.getName (*mongo-config* :db))))))))

(deftest closing-with-mongo
  (with-test-mongo
    (let [a (make-connection "congomongotest-db-a" :host test-db-host :port test-db-port)]
      (with-mongo a
        (testing "close-connection inside with-mongo sets mongo-config to nil"
          (close-connection a)
          (is (= nil *mongo-config*)))))))

(deftest query-options
  (are [x y] (= (calculate-query-options x) y)
       nil 0
       [] 0
       [:tailable] 2
       [:tailable :slaveok] 6
       [:tailable :slaveok :notimeout] 22
       :notimeout 16))

(deftest fetch-with-options
  (with-test-mongo
    (insert! :thingies {:foo 1})
    (is (= 1 (-> (fetch :thingies :where {:foo 1} :options nil) first :foo)))
    (is (= 1 (-> (fetch :thingies :where {:foo 1} :options []) first :foo)))
    (is (= 1 (-> (fetch :thingies :where {:foo 1} :options :notimeout) first :foo)))
    (is (= 1 (-> (fetch :thingies :where {:foo 1} :options [:notimeout]) first :foo)))))

(deftest test-fetch-and-modify
  (with-test-mongo
    (insert! :test_col {:key "123"
                        :value 1})
    (fetch-and-modify :test_col {:key "123"} {:$inc {:value 2}})
    (is (= 3 (:value (fetch-one :test_col :where {:key "123"}))))
    (let [res (fetch-and-modify :test_col {:key "123"} {:$inc {:value 1}} :only [:value] :return-new? true)]
      (is (not (contains? res :key)))
      (is (= 4 (:value res))))))

(deftest collection-existence
  (with-test-mongo
    (insert! :notbogus {:foo "bar"})
    (is (collection-exists? :notbogus))
    (is (not (collection-exists? :bogus)))
    (create-collection! :no-options-so-deferred-creation)
    (is (not (collection-exists? :no-options-so-deferred-creation)))))

(deftest capped-collections
  (with-test-mongo
    (create-collection! :cappedcoll :capped true :max 2 :size 1000)
    (is (collection-exists? :cappedcoll))
    (insert! :cappedcoll {:foo 1 :bar 1})
    (insert! :cappedcoll {:foo 1 :bar 2})
    (insert! :cappedcoll {:foo 1 :bar 3})
    (let [results (fetch :cappedcoll :where {:foo 1})]
      (is (= [2 3] (map :bar (take 2 results)))))))

(deftest fetch-sort
  (with-test-mongo
    (let [unsorted [3 10 7 0 2]]
      (mass-insert! :points
                  (for [i unsorted]
                    {:x i}))
      (is (= (map :x (fetch :points :sort {:x 1})) (sort unsorted)))
      (is (= (map :x (fetch :points :sort {:x -1})) (reverse (sort unsorted)))))))

(deftest fetch-with-only
  (with-test-mongo
    (let [data {:_id 10 :foo "clever" :bar "filter"}
          id (:_id data)]
      (insert! :with-only data)
      (are [data-keys select-clause] (= (select-keys data data-keys)
                                        (fetch-one :with-only :only select-clause))
           [:_id :foo] [:foo]
           [:foo :bar] {:_id false}
           [:_id :bar] {:foo false}))))

(deftest fetch-by-id-of-any-type
  (with-test-mongo
    (insert! :by-id {:_id "Blarney" :val "Stone"})
    (insert! :by-id {:_id 300 :val "warriors"})
    (is (= "Stone" (:val (fetch-by-id :by-id "Blarney"))))
    (is (= "warriors" (:val (fetch-by-id :by-id 300))))))

(deftest fetch-by-ids-of-any-type
  (with-test-mongo
    (insert! :by-ids {:_id "Blarney" :val "Stone"})
    (insert! :by-ids {:_id 300 :val "warriors"})
    (is (= #{"Stone" "warriors"} (set (map :val (fetch-by-ids :by-ids ["Blarney" 300])))))))

(deftest eager-ref-fetching
  (let [fetch-eagerly       (with-ref-fetching fetch)
        fetch-eagerly-by-id (with-ref-fetching fetch-by-id)
        command-eagerly     (with-ref-fetching command)]
    (with-test-mongo
      (insert! :users {:_id "js" :name "John Smith" :email "jsmith@foo.bar"})
      (insert! :users {:_id "jd" :name "Jane Doe"   :email "jdoe@foo.bar"})
      (insert! :posts {:_id "p1"
                       :user (db-ref :users "js")
                       :comment "great site!"
                       :location [-10.001 -20.001]})
      (insert! :posts {:_id "p2"
                       :user (db-ref :users "jd")
                       :comment "I agree..."
                       :location [10.001 20.001]})
      (add-index! :posts [[:location "2d"]])

      ;; leave db-refs alone, assumes manual, lazy fetching
      (is (db-ref? (-> (fetch :posts :where {:comment "great site!"}) first :user)))
      (is (db-ref? (-> (fetch :posts :where {:comment "I agree..."})  first :user)))

      ;; eagerly fetch db-refs
      (is (map?           (-> (fetch-eagerly :posts :where {:comment "great site!"}) first :user)))
      (is (= "John Smith" (-> (fetch-eagerly :posts :where {:comment "great site!"}) first :user :name)))
      (is (map?           (-> (fetch-eagerly :posts :where {:comment "I agree..."})  first :user)))
      (is (= "Jane Doe"   (-> (fetch-eagerly :posts :where {:comment "I agree..."})  first :user :name)))

      ;; the decorator works on existing retrieval fns
      (is (db-ref? (:user (fetch-by-id         :posts "p1"))))
      (is (map?    (:user (fetch-eagerly-by-id :posts "p1"))))

      ;; it also works on seq results
      (is (db-ref? (-> (fetch :posts)         first :user)))
      (is (map?    (-> (fetch-eagerly :posts) first :user)))

      ;; and on database commands
      (let [earth-radius (* 6378 1000) ; in meters
            radians      (fn [meters]
                           (float (/ meters earth-radius)))
            cmd          {:geoNear     :posts
                          :near        [10 20]
                          :spherical   true
                          :maxDistance (radians 1000)}
            lazy-result  (command cmd)
            eager-result (command-eagerly cmd)]
        (is (db-ref?      (-> lazy-result  :results first :obj :user)))
        (is (map?         (-> eager-result :results first :obj :user)))
        (is (= "Jane Doe" (-> eager-result :results first :obj :user :name)))))))

`(deftest databases-test
  (with-test-mongo
    (let [test-db2 "congomongotestdb-part-deux"]

      (is (= test-db (.getName (*mongo-config* :db)))
          "default DB exists")
      (set-database! test-db2)

      (is (= test-db2 (.getName (*mongo-config* :db)))
          "changed DB exists")
      (drop-database! test-db2))))

(defn make-points! []
  (println "slow insert of 10000 points:")
  (time
   (doseq [x (range 100)
           y (range 100)]
     (insert! :points {:x x :y y}))))

(deftest slow-insert-and-fetch
  (with-test-mongo
    (make-points!)
    (is (= (* 100 100)) (fetch-count :points))
    (is (= (fetch-count :points
                        :where {:x 42}) 100))))

(deftest destroy
  (with-test-mongo
    (make-points!)
    (let [point-id (:_id (fetch-one :points))]
      (destroy! :points
                {:_id point-id})
      (is (= (fetch-count :points) (dec (* 100 100))))
      (is (= nil (fetch-one :points
                            :where {:_id point-id}))))))

(deftest update
  (with-test-mongo
    (make-points!)
    (let [point-id (:_id (fetch-one :points))]
      (update! :points
               {:_id point-id}
               {:x "suffusion of yellow"})
      (is (= (:x (fetch-one :points
                            :where {:_id point-id}))
             "suffusion of yellow")))))


(deftest test-distinct-values
  (with-test-mongo
    (insert! :distinct {:genus "Pan" :species "troglodytes" :common-name "chimpanzee"})
    (insert! :distinct {:genus "Pan" :species "pansicus" :common-name "bonobo"})
    (insert! :distinct {:genus "Homo" :species "sapiens" :common-name "human"})
    (insert! :distinct {:genus "Homo" :species "floresiensis" :common-name "hobbit"})

    (is (= (set (distinct-values :distinct "genus"))
           #{"Pan" "Homo"}))
    (is (= (set (distinct-values :distinct "common-name"))
           #{"chimpanzee" "bonobo" "human" "hobbit"}))
    (is (= (set (distinct-values :distinct "species" :where {:genus "Pan"}))
           #{"troglodytes" "pansicus"}))
    (is (= (set (distinct-values :distinct "species" :where "{\"genus\": \"Pan\"}" :from :json))
           #{"troglodytes" "pansicus"}))
    (let [json (distinct-values :distinct "genus" :as :json)]
      ;; I don't think you can influence the order in which distinct results are returned,
      ;; so just check both possibilities
      (is (or (= (read-json json) ["Pan", "Homo"])
              (= (read-json json) ["Homo", "Pan"]))))))


;; ;; mass insert chokes on excessively large inserts
;; ;; will need to implement some sort of chunking algorithm

(deftest mass-insert
  (with-test-mongo
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

(deftest insert-for-side-effects-only
  (with-test-mongo
    (is (nil? (insert! :beers {:beer "Franziskaner" :wheaty true} :to nil)))))

(deftest basic-indexing
  (with-test-mongo
    (make-points!)
    (add-index! :points [:x])
    (is (some #(= (into {} (% "key")) {"x" 1})
              (get-indexes :points)))))

(defn- get-index
  "Retrieve an index, either by name or by key vector"
  [coll index]
  (let [selector (if (vector? index)
                   (fn [i]
                     (= (get i "key")
                        (coerce-index-fields index)))
                   (fn [i]
                     (= (get i "name")
                        index)))]
    (first (filter selector (get-indexes coll)))))


(deftest complex-indexing
  (with-test-mongo
    (add-index! :testing-indexes [:a :b :c])
    (let [auto-generated-index-name "a_1_b_1_c_1"
          actual-index (get (get-index :testing-indexes auto-generated-index-name)
                            "key")
          expected-index (doto (BasicDBObject.)
                           (.put "a" 1)
                           (.put "b" 1)
                           (.put "c" 1))]
      (is (= (.toString actual-index) (.toString expected-index))))

    (add-index! :testing-indexes [:a [:b -1] :c])
    (let [auto-generated-index-name "a_1_b_-1_c_1"
          actual-index (get (get-index :testing-indexes auto-generated-index-name)
                            "key")
          expected-index (doto (BasicDBObject.)
                           (.put "a" 1)
                           (.put "b" -1)
                           (.put "c" 1))]
      (is (= (.toString actual-index) (.toString expected-index))))))

(deftest index-name
  (with-test-mongo
    (let [coll :test-index-name
          index "customIndexName"]
      (add-index! coll [:foo :bar :baz] :name index)
      (is (= (get (get-index coll index)
                  "key"))))))

(deftest test-delete-index
  (with-test-mongo
    (let [test-collection :testing-indexes
          index-name "test_index"
          index-key [:c :b [:a -1]]]
      ;; Test using keys
      (is (nil? (get-index test-collection index-key)))
      (add-index! test-collection index-key)
      (is (get-index test-collection index-key))
      (drop-index! test-collection index-key)
      (is (nil? (get-index test-collection index-key)))

      ;; Test using names
      (is (nil? (get-index test-collection index-name)))
      (add-index! test-collection index-key :name index-name)
      (is (get-index test-collection index-name))
      (drop-index! test-collection index-name)
      (is (nil? (get-index test-collection index-name))))))


(defrecord Foo [a b])

(deftest can-insert-records-as-maps
  (with-test-mongo
    (insert! :foos (Foo. 1 2))
    (let [found (fetch-one :foos)]
      (are (= 1 (:a found))
           (= 2 (:b found))))))

(deftest insert-returns-id
  (with-test-mongo
    (let [ret (insert! :foos {:a 1 :b 2})]
      (is (map? ret))
      (is (= (:a ret) 1))
      (is (= (:b ret) 2))
      (is (:_id ret)))))

(deftest gridfs-insert-and-fetch
  (with-test-mongo
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
  (with-test-mongo
    (insert-file! :testfs (.getBytes "banana") :filename "lunch")
    (destroy-file! :testfs {:filename "lunch"})
    (is (empty? (fetch-files :testfs)))))

(deftest gridfs-insert-with-metadata
  (with-test-mongo
    (let [f (insert-file! :testfs (.getBytes "nuts")
                          :metadata {:calories 50, :opinion "tasty"})]
      (is (= "tasty" (get-in f [:metadata :opinion])))
      (is (= f (fetch-one-file :testfs :where { :metadata.opinion "tasty" }))))))

(deftest gridfs-write-file-to
  (with-test-mongo
    (let [f (insert-file! :testfs (.getBytes "banana"))]
      (let [o (java.io.ByteArrayOutputStream.)]
        (write-file-to :testfs f o)
        (is (= "banana" (str o)))))))

(deftest gridfs-stream-from
  (with-test-mongo
    (let [f (insert-file! :testfs (.getBytes "plantain"))
          stream (stream-from :testfs f)
          data (slurp stream)]
      (is (= "plantain" data)))))

(defn- gen-tempfile []
  (let [tmp (doto
                (java.io.File/createTempFile "test" ".data")
              (.deleteOnExit))]
    (with-open [w (java.io.FileOutputStream. tmp)]
      (doseq [i (range 2048)]
        (.write w (rem i 255))))
    tmp))

(deftest gridfs-test-insert-different-data-types
  (with-test-mongo
    (let [file (gen-tempfile)]
      (insert-file! :filefs file)
      (insert-file! :filefs (java.io.FileInputStream. file))
      (insert-file! :filefs (.getBytes "data"))
      (is (= 3 (count (fetch-files :filefs)))))))

(deftest test-roundtrip-vector
  (with-test-mongo
    (insert! :stuff {:name "name" :vector [ "foo" "bar"]})
    (let [return (fetch-one :stuff :where {:name "name"})]
      (is (vector? (:vector return))))))

;; Note: with Clojure 1.3.0, 1.0 != 1 and the JS stuff returns floating point numbers instead
;; of integers so I've changed the tests to use floats in the expected values - except for the
;; 1000000 value which _does_ come back as an integer! -- Sean Corfield

(deftest test-map-reduce
  (with-test-mongo
    (insert! :mr {:fruit "bananas" :count 1})
    (insert! :mr {:fruit "bananas" :count 2})
    (insert! :mr {:fruit "plantains" :count 3})
    (insert! :mr {:fruit "plantains" :count 2})
    (insert! :mr {:fruit "pineapples" :count 4})
    (insert! :mr {:fruit "pineapples" :count 2})
    (let [mapfn
          "function(){
              emit(this.fruit, {count: this.count});
          }"
          mapfn-with-scope
          "function(){
              emit((adj + ' ' + this.fruit), {count: this.count});
          }"
          reducefn
          "function(key, values){
              var total = 0;
              for ( var i=0; i<values.length; i++ ){
                  total += values[i].count;
              }
              return { count : total };
          }"
          target-collection :monkey-shopping-list]
      ;; See that the base case works
      (is (= (map-reduce :mr mapfn reducefn target-collection)
             (seq [{:_id "bananas" :value {:count 3.0}}
                   {:_id "pineapples" :value {:count 6.0}}
                   {:_id "plantains" :value {:count 5.0}}])))
      ;; Make sure we get the collection name back, too
      (is (= (map-reduce :mr mapfn reducefn target-collection :output :collection)
             target-collection))

      ;; Test the new (>= MongoDB 1.8) MapReduce output options
      ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
      ;; Replace existing data in target collection
      (drop-coll! target-collection)
      (insert! target-collection {:dummy-data true}) ;; we should not find this!
      (is (= (map-reduce :mr mapfn reducefn {:replace target-collection})
             (seq [{:_id "bananas" :value {:count 3.0}}
                   {:_id "pineapples" :value {:count 6.0}}
                   {:_id "plantains" :value {:count 5.0}}])))

      ;; Merge data in the target collection
      (drop-coll! target-collection)
      (insert! target-collection {:_id "macadamia nuts" :value {:count 1000000}})
      (is (= (map-reduce :mr mapfn reducefn {:merge target-collection})
             (seq [{:_id "macadamia nuts" :value {:count 1000000}}
                   {:_id "bananas" :value {:count 3.0}}
                   {:_id "pineapples" :value {:count 6.0}}
                   {:_id "plantains" :value {:count 5.0}}])))

      ;; Reduce with existing data
      (drop-coll! target-collection)
      (insert! target-collection {:_id "bananas" :value {:count 10}})
      (is (= (map-reduce :mr mapfn reducefn {:reduce target-collection})
             (seq [{:_id "bananas" :value {:count 13.0}}
                   {:_id "pineapples" :value {:count 6.0}}
                   {:_id "plantains" :value {:count 5.0}}])))

      ;; inline data (no output collection)
      (is (= (map-reduce :mr mapfn reducefn {:inline 1})
             (seq [{:_id "bananas" :value {:count 3.0}}
                   {:_id "pineapples" :value {:count 6.0}}
                   {:_id "plantains" :value {:count 5.0}}])))

      ;; inline output ignores if you ask for an output collection name instead
      (is (= (map-reduce :mr mapfn reducefn {:inline 1} :output :collection)
             (seq [{:_id "bananas" :value {:count 3.0}}
                   {:_id "pineapples" :value {:count 6.0}}
                   {:_id "plantains" :value {:count 5.0}}])))

      ;; Check limit
      (is (= (map-reduce :mr mapfn reducefn target-collection :limit 2)
             (seq [{:_id "bananas" :value {:count 3.0}}])))
      ;; Check sort
      ;; sort requires an index to work?
      (add-index! :mr [:fruit])
      (is (= (map-reduce :mr mapfn reducefn target-collection :sort {:fruit -1} :limit 2)
             (seq [{:_id "plantains" :value {:count 5.0}}])))
      ;; check query
      (is (= (map-reduce :mr mapfn reducefn target-collection :query {:fruit "pineapples"})
             (seq [{:_id "pineapples" :value {:count 6.0}}])))
      ;; check finalize
      (is (= (map-reduce :mr mapfn reducefn target-collection
                         :finalize "function(key, value){return 'There are ' + value.count + ' ' + key}")
             (seq [{:_id "bananas" :value "There are 3 bananas"}
                   {:_id "pineapples" :value "There are 6 pineapples"}
                   {:_id "plantains" :value "There are 5 plantains"}])))
      ;; check scope
      (is (= (map-reduce :mr mapfn-with-scope reducefn target-collection
                         :scope {:adj "tasty"})
             (seq [{:_id "tasty bananas" :value {:count 3.0}}
                   {:_id "tasty pineapples" :value {:count 6.0}}
                   {:_id "tasty plantains" :value {:count 5.0}}])))
      )))

(deftest test-server-eval
  (with-test-mongo
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
") 625.0))))

(deftest dup-key-exception-works
  (add-index! :dup-key-coll [:unique-col] :unique true)
  (let [obj {:unique-col "some string"}]

    ;; first one, should succeed
    (try
      (insert! :dup-key-coll obj)
      (is true)
      (catch Exception e
        (is false)))

    (try
      (insert! :dup-key-coll obj)
      (is false)
      (catch Exception e
        (is true)))))
