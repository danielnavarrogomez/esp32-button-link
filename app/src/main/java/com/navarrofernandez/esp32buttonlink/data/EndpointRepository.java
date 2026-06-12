package com.navarrofernandez.esp32buttonlink.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

public class EndpointRepository {
    public static final int MAX_ENDPOINTS = 10;
    private static final String PREFS_NAME = "esp32_button_link_endpoints";
    private static final String KEY_ENDPOINTS = "endpoints";

    private final SharedPreferences preferences;

    public EndpointRepository(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public List<EndpointConfig> load() {
        List<EndpointConfig> endpoints = new ArrayList<>();
        String raw = preferences.getString(KEY_ENDPOINTS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length() && endpoints.size() < MAX_ENDPOINTS; i++) {
                endpoints.add(EndpointConfig.fromJson(array.getJSONObject(i)));
            }
        } catch (JSONException ignored) {
        }
        return endpoints;
    }

    public void save(List<EndpointConfig> endpoints) {
        JSONArray array = new JSONArray();
        int count = Math.min(endpoints.size(), MAX_ENDPOINTS);
        for (int i = 0; i < count; i++) {
            try {
                array.put(endpoints.get(i).toJson());
            } catch (JSONException ignored) {
            }
        }
        preferences.edit().putString(KEY_ENDPOINTS, array.toString()).apply();
    }
}
