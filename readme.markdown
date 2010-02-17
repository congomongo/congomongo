CongoMongo
===========

What?
------
A toolkit for using MongoDB with Clojure.

Recent Changes
--------------

GridFs support courtesy of Steve Purcell. Thannks, Steve!
Bumped version to 0.1.2.
Clojars group is now congomongo.           

Summary
---------
note: The .java bits are likely to disappear once Clojure 1.2 is stable.

CongoMongo is essentially two parts.

One is the ClojureDBObject class written in java.
It extends the BasicDBObject class with two methods (putClojure,
toClojure) and a convenience constructor.

It is basically a close-to-the-metal wrapper around the mongo-java-driver's
BasicDBObject class to handle coercions (keyword->string and nested structures)        
while offering similar performance to the BasicDBObject class itself.

The rest is a Clojure api for the mongo-java-driver.

Basics
--------

### Setup

    (ns my-mongo-app  
      (:use somnium.congomongo))  
    (mongo!  
      :db "mydb") 

### Simple Tasks
------------------

#### create

    (insert! :robots    
             {:name "robby"}

#### read

    (def my-robot (fetch-one :robots)) => #'user/my-robot

    my-robot => { :name "robby", 
                  :_id  #<ObjectId> "0c23396f7e53e34a4c8cf400">, 
                  :_ns  "robots"}

#### update

    (update! :robots my-robot (merge my-robot { :name "asimo" }))

    =>  { :name "asimo" , 
          :_id  #<ObjectId> "0c23396f7e53e34a4c8cf400"> , 
          :_ns : "robots" }

#### destroy

    (destroy! my-robot) => nil
    (fetch :robots) => ()

### More Sophisticated Tasks
----------------------------

#### mass inserts

    (mass-insert!  
      :points
      (for [x (range 100) y (range 100)] 
        {:x x 
         :y y 
         :z (* x y)})) 

     =>  nil

    (fetch-count :points)
    => 10000

#### ad-hoc queries

    (fetch-one
      :points
      :where {:x {:$gt 10  
                  :$lt 20}
              :y 42
              :z {:$gt 500}})

    => {:x 12, :y 42, :z 504,  :_ns "points", :_id ... }

#### easy json
------------------------------------------------------------------------

    (fetch-one :points 
               :as :json)

    => "{ \"_id\" : \"0c23396ffe79e34a508cf400\" , 
          \"x\" : 0 , \"y\" : 0 , \"z\" : 0 , \"_ns\" : \"points\"}"

   
Install
-------

Leiningen is the recommended way to use congomongo.
Just add 
    [congomongo "0.1.2-SNAPSHOT"]
to your project.clj and do
    $lein deps
to get congomongo and all of its dependencies.    

TODO
----

* convenient dsl for advanced queries/features 
* orm-like schemas/validations?

### Feedback

CongoMongo is a work in progress. If you've used, improved, 
or abused it I'd love to hear about it. Contact me at boekhoffa@gmail.com
