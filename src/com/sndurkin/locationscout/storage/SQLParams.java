package com.sndurkin.locationscout.storage;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

// This class holds a map of named parameters to a SQLite query.
public class SQLParams {

    protected Map<String, Object> params = new HashMap<>();

    public SQLParams() { }

    public void put(String paramName, Object paramValue) {
        params.put("$" + paramName + "$", paramValue);
    }

    // Returns a list of selection args which can be passed into SQLiteDatabase.rawQuery().
    public String[] getParamsOrderedByQuery(String query) {
        Map<Integer, Object> paramsByIndex = new TreeMap<>();
        for(Map.Entry<String, Object> entry : params.entrySet()) {
            String paramName = entry.getKey();
            int idx = query.indexOf(paramName);
            while(idx >= 0) {
                paramsByIndex.put(idx, entry.getValue());
                idx = query.indexOf(paramName, idx + paramName.length());
            }
        }

        List<String> selectionArgs = new ArrayList<>();
        for(Map.Entry<Integer, Object> entry : paramsByIndex.entrySet()) {
            Object param = entry.getValue();
            if(param instanceof List) {
                for(Object p : (List<?>) param) {
                    selectionArgs.add(p.toString());
                }
            }
            else if(param instanceof JSONArray) {
                JSONArray arr = (JSONArray) param;
                for(int i = 0; i < arr.length(); ++i) {
                    Object v = arr.opt(i);
                    selectionArgs.add(v != null ? v.toString() : null);
                }
            }
            else if(param instanceof String[]) {
                for(String s : (String[]) param) {
                    selectionArgs.add(s);
                }
            }
            else if(param instanceof Long[]) {
                for(Long l : (Long[]) param) {
                    selectionArgs.add(l.toString());
                }
            }
            else {
                selectionArgs.add(param.toString());
            }
        }
        return selectionArgs.toArray(new String[0]);
    }

    // Iterates the query string and replaces named variables with the appropriate
    // number of '?' characters needed for SQLiteDatabase.rawQuery().
    public String massageQueryForExecution(String query) {
        for(Map.Entry<String, Object> entry : params.entrySet()) {
            Object param = entry.getValue();
            if(param instanceof List) {
                String argPlacementsStr = getArgPlacementsStr(((List<?>) param).size());
                query = query.replace(entry.getKey(), "(" + argPlacementsStr + ")");
            }
            else if(param instanceof JSONArray) {
                String argPlacementsStr = getArgPlacementsStr(((JSONArray) param).length());
                query = query.replace(entry.getKey(), "(" + argPlacementsStr + ")");
            }
            else if(param instanceof String[]) {
                String argPlacementsStr = getArgPlacementsStr(((String[]) param).length);
                query = query.replace(entry.getKey(), "(" + argPlacementsStr + ")");
            }
            else if(param instanceof Long[]) {
                String argPlacementsStr = getArgPlacementsStr(((Long[]) param).length);
                query = query.replace(entry.getKey(), "(" + argPlacementsStr + ")");
            }
            else {
                query = query.replace(entry.getKey(), "?");
            }
        }
        return query;
    }

    // Returns a String of arg placeholders for a query. For example, getArgPlacementsStr(3) yields "?,?,?"
    protected String getArgPlacementsStr(int num) {
        String argPlacements = "";
        for(int i = 0; i < num; ++i) {
            if (i > 0) {
                argPlacements += ",";
            }
            argPlacements += "?";
        }
        return argPlacements;
    }

}
