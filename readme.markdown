CongoMongo
===========

What?
------
A toolkit for using MongoDB with Clojure.

Summary
---------
CongoMongo is essentially two parts.

One is the ClojureDBObject class written java.
It extends the BasicDBObject class with two methods (putClojure,
toClojure) and a convenience constructor.
It's fast: 
coerces 100,000 maps (clojure -> ClojureDBObject -> clojure) in under 2
seconds,   
and convenient, automatically handling keyword-keys and arbitrarily nested
structures.

The other is a clojure wrapper of the mongo-java-driver.   
Currently there is support for CRUD, indexing, and error checking.   
More to come.

Recent Changes
--------------
Keyword coercions for map keys are now handled automatically (and can
be disabled).   

Keywords in value fields are currently converted to strings for
safety, but not preserved as keywords in mongo. There is a facility
for serializing custom types, and it can be added if there is a
demand. 

(Clojure code serializes to strings easily enough that I haven't
needed this yet.)

Switched to keyword arguments for central functions in order to
streamline the api and make it easier to cover less common use cases.

Coming Changes
--------------
Current goal is to wrap more of the core mongo api.
This includes convenience functions for grouping, sorting,   
map-reduce, server-side javascript commands and more.

### Patches
The current api should not be considered stable, but I will try to fix any
bugs submitted by people trying out the current version. 

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

   
Dependencies
------------

CongoMongo depends on the mongodb-java-driver, clojure, and clojure-contrib.       
Currently CongoMongo only works with the clojure 1.1 branch.     

A mongodb-java-driver jar is included in lib, and you can also get
the bleeding edge version [here](http://www.github.com/mongodb/mongo-java-driver).   
Just in case you don't have clojure try looking [here](http://www.github.com/richhickey/clojure).

Building congomongo
-------

No build required. 

Just put congomongo.jar and the mongo-java-driver
on your classpath along with your preferred clojure jars and you're
good to go. If you feel compelled to compile your own ClojureDBObject
the source is in ./src/main/java

To run the test suite will need a running MongoDB instance, ant, and
symlinks to clojure and clojure-contrib in ./lib.
Once that's been taken care of just type:

  ant test

TODO
----

* convenient dsl for advanced queries/features 
* orm-like schemas/validations?

### Feedback

CongoMongo is a work in progress. If you've used, improved, 
or abused it I'd love to hear about it. Contact me at somnium@gmx.us
