package org.json;
import java.util.*;
public class JSONObject {
    private final Map<String,Object> values=new HashMap<>();
    public JSONObject put(String key,Object value) { values.put(key,value); return this; }
    public JSONObject put(String key,boolean value) { return put(key,Boolean.valueOf(value)); }
    public JSONObject put(String key,int value) { return put(key,Integer.valueOf(value)); }
    public boolean optBoolean(String key) { return Boolean.TRUE.equals(values.get(key)); }
    public String optString(String key) { Object v=values.get(key); return v==null ? "" : v.toString(); }
    public int optInt(String key) { Object v=values.get(key); return v instanceof Number ? ((Number)v).intValue() : 0; }
    public String toString() { return values.toString(); }
}
