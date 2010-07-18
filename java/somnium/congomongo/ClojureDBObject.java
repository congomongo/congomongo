/*
 * Subclass of com.mongodb.BasicDBObject for
 * rapid creation of clojure persistent data structures.
 */

package somnium.congomongo;
import com.mongodb.BasicDBObject;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Arrays;
import clojure.lang.Keyword;
import clojure.lang.MapEntry;
import clojure.lang.IPersistentMap;


/**
 *
 * @author somnium
 */

public class ClojureDBObject extends BasicDBObject {

  public ClojureDBObject() {
  }

  public ClojureDBObject(IPersistentMap m) {
    this.putClojure(m);
  }

  public void putClojure(IPersistentMap m) {
    Iterator<MapEntry> iter = m.iterator();
    while (iter.hasNext()) {
        MapEntry pair = iter.next();
        Object cKey = pair.getKey();
        String mKey;

      // check key for keyword type
      // and assign result to var mKey

      if (cKey instanceof Keyword) {
        mKey = ((Keyword)cKey).getName();}
      else {mKey = cKey.toString();
      }

      Object v = pair.getValue();

        // delegate to readClojureObject

      this.put(mKey, readClojureObject(v));
      }
    }

    private Object readClojureObject(Object o) {

      if (o instanceof Keyword) {
        return ((Keyword)o).getName();
      }

      else if (o instanceof IPersistentMap) {
        ClojureDBObject clj = new ClojureDBObject((IPersistentMap) o);
        return clj;
      }

      else if (o instanceof List) {
        int msize = ((List)o).size();
        Object[] ary = new Object[msize];
        Iterator<Object> iter = ((List)o).iterator();
        for (int idx = 0; idx < msize; idx++) {
          Object obj = iter.next();
          ary[idx] = readClojureObject(obj);
        }
        return Arrays.asList(ary);
      }

      else return o;

    }

    // options:
    // use struct-map?
    // keywordize keys?
    // call .toString on ObjectIds?

    public IPersistentMap toClojure() {
      return toClojureMap(this, true);
    }

    public IPersistentMap toClojure(boolean keywordize) {
      return toClojureMap(this, keywordize);
    }

    public static IPersistentMap toClojureMap(Map m, boolean keywordize){
      int msize = m.size() * 2;
      Object[] ary = new Object[msize];
      Set keys = m.keySet();
      Iterator<String> iter = keys.iterator();
      for (int idx = 0; idx < msize; idx++) {
        String s = iter.next();
        Object v = m.get(s);

        if (keywordize) {
          Keyword k = Keyword.intern(s);
          ary[idx] = k;
        } else {
          ary[idx] = s;
        }
        idx++;
        ary[idx] = toClojureVal(v, keywordize);
      }

      return clojure.lang.RT.map(ary);
   
    }

    private static Object toClojureVal(Object o, boolean keywordize) {

      if (o instanceof Map) {
        return toClojureMap((Map)o, keywordize);
      }

      else if (o instanceof List) {
        int msize = ((List)o).size();
        Object[] ary = new Object[msize];

        Iterator<Object> i = ((java.util.List) o).iterator();
        for (int idx = 0; idx < msize; idx ++) {
          Object obj = i.next();
          ary[idx] = toClojureVal(obj, keywordize);
        }

      return clojure.lang.RT.vector(ary);

      }

      else return o;
    }
  }