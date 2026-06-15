package com.radio.app.network;

import com.radio.app.models.RadioStation;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RadioApiService {
    private static final String BASE_URL = "https://de1.api.radio-browser.info/";
    private static RadioApiService instance;
    private ExecutorService executor;

    private RadioApiService() {
        executor = Executors.newCachedThreadPool();
    }

    public static synchronized RadioApiService getInstance() {
        if (instance == null) instance = new RadioApiService();
        return instance;
    }

    public interface ApiCallback<T> {
        void onSuccess(T result);
        void onError(String error);
    }

    public void getChineseStations(int limit, int offset, ApiCallback<List<RadioStation>> callback) {
        executor.execute(() -> {
            try {
                URL url = new URL(BASE_URL + "json/stations/bylanguage/chinese?limit=" + limit + "&offset=" + offset);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "RadioApp/1.0");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();

                    JSONArray array = new JSONArray(sb.toString());
                    List<RadioStation> stations = new ArrayList<>();
                    for (int i = 0; i < array.length(); i++) {
                        stations.add(RadioStation.fromJson(array.getJSONObject(i)));
                    }
                    callback.onSuccess(stations);
                } else {
                    callback.onError("HTTP " + conn.getResponseCode());
                }
                conn.disconnect();
            } catch (Exception e) {
                callback.onError(e.getMessage());
            }
        });
    }
}
