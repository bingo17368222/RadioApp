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

    public void getAllStations(ApiCallback<List<RadioStation>> callback) {
        executor.execute(() -> {
            List<RadioStation> allStations = new ArrayList<>();
            List<RadioStation> builtin = getBuiltinStations();
            
            // 添加内置电台（不依赖网络，立即显示）
            allStations.addAll(builtin);
            
            // 先返回内置电台，让用户立即看到内容
            if (!allStations.isEmpty()) {
                callback.onSuccess(allStations);
            }
            
            // 从API获取中文电台（后台补充）
            try {
                URL url = new URL(BASE_URL + "json/stations/bylanguage/chinese?limit=50&offset=0");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "RadioApp/1.0");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();

                    JSONArray array = new JSONArray(sb.toString());
                    for (int i = 0; i < array.length(); i++) {
                        RadioStation s = RadioStation.fromJson(array.getJSONObject(i));
                        if (s.isLastCheckOk()) {
                            s.setCurrentProgram("Live Stream");
                            allStations.add(s);
                        }
                    }
                }
                conn.disconnect();
            } catch (Exception e) {
                e.printStackTrace();
            }
            
            // 从API获取河南电台
            try {
                URL url = new URL(BASE_URL + "json/stations/search?country=China&state=Henan&limit=30");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "RadioApp/1.0");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();

                    JSONArray array = new JSONArray(sb.toString());
                    for (int i = 0; i < array.length(); i++) {
                        RadioStation s = RadioStation.fromJson(array.getJSONObject(i));
                        if (s.isLastCheckOk()) {
                            s.setCurrentProgram("河南电台");
                            allStations.add(s);
                        }
                    }
                }
                conn.disconnect();
            } catch (Exception e) {
                e.printStackTrace();
            }
            
            // 网络请求完成后再次回调（更新完整列表）
            if (allStations.size() > builtin.size()) {
                callback.onSuccess(allStations);
            }
        });
    }
    
    private List<RadioStation> getBuiltinStations() {
        List<RadioStation> list = new ArrayList<>();
        
        // 河南人民广播电台
        RadioStation s1 = new RadioStation();
        s1.setId("henan-1");
        s1.setName("河南新闻广播");
        s1.setStreamUrl("http://lhttp.qingting.fm/live/5022037/64k.mp3");
        s1.setCountry("China");
        s1.setBitrate(64);
        s1.setLastCheckOk(true);
        s1.setCurrentProgram("河南新闻");
        list.add(s1);
        
        RadioStation s2 = new RadioStation();
        s2.setId("henan-2");
        s2.setName("河南音乐广播");
        s2.setStreamUrl("http://lhttp.qingting.fm/live/5022041/64k.mp3");
        s2.setCountry("China");
        s2.setBitrate(64);
        s2.setLastCheckOk(true);
        s2.setCurrentProgram("河南音乐");
        list.add(s2);
        
        RadioStation s3 = new RadioStation();
        s3.setId("henan-3");
        s3.setName("河南交通广播");
        s3.setStreamUrl("http://lhttp.qingting.fm/live/5022043/64k.mp3");
        s3.setCountry("China");
        s3.setBitrate(64);
        s3.setLastCheckOk(true);
        s3.setCurrentProgram("河南交通");
        list.add(s3);
        
        RadioStation s4 = new RadioStation();
        s4.setId("henan-4");
        s4.setName("河南经济广播");
        s4.setStreamUrl("http://lhttp.qingting.fm/live/5022045/64k.mp3");
        s4.setCountry("China");
        s4.setBitrate(64);
        s4.setLastCheckOk(true);
        s4.setCurrentProgram("河南经济");
        list.add(s4);
        
        RadioStation s5 = new RadioStation();
        s5.setId("henan-5");
        s5.setName("郑州新闻广播");
        s5.setStreamUrl("http://lhttp.qingting.fm/live/5022051/64k.mp3");
        s5.setCountry("China");
        s5.setBitrate(64);
        s5.setLastCheckOk(true);
        s5.setCurrentProgram("郑州新闻");
        list.add(s5);
        
        RadioStation s6 = new RadioStation();
        s6.setId("henan-6");
        s6.setName("洛阳交通广播");
        s6.setStreamUrl("http://lhttp.qingting.fm/live/5022055/64k.mp3");
        s6.setCountry("China");
        s6.setBitrate(64);
        s6.setLastCheckOk(true);
        s6.setCurrentProgram("洛阳交通");
        list.add(s6);
        
        // 央广电台
        RadioStation s7 = new RadioStation();
        s7.setId("cnr-1");
        s7.setName("中国之声");
        s7.setStreamUrl("http://lhttp.qingting.fm/live/386/64k.mp3");
        s7.setCountry("China");
        s7.setBitrate(64);
        s7.setLastCheckOk(true);
        s7.setCurrentProgram("中国之声");
        list.add(s7);
        
        RadioStation s8 = new RadioStation();
        s8.setId("cnr-2");
        s8.setName("经济之声");
        s8.setStreamUrl("http://lhttp.qingting.fm/live/387/64k.mp3");
        s8.setCountry("China");
        s8.setBitrate(64);
        s8.setLastCheckOk(true);
        s8.setCurrentProgram("经济之声");
        list.add(s8);
        
        RadioStation s9 = new RadioStation();
        s9.setId("cnr-3");
        s9.setName("音乐之声");
        s9.setStreamUrl("http://lhttp.qingting.fm/live/388/64k.mp3");
        s9.setCountry("China");
        s9.setBitrate(64);
        s9.setLastCheckOk(true);
        s9.setCurrentProgram("音乐之声");
        list.add(s9);
        
        // 其他省台
        RadioStation s10 = new RadioStation();
        s10.setId("other-1");
        s10.setName("北京新闻广播");
        s10.setStreamUrl("http://lhttp.qingting.fm/live/332/64k.mp3");
        s10.setCountry("China");
        s10.setBitrate(64);
        s10.setLastCheckOk(true);
        s10.setCurrentProgram("北京新闻");
        list.add(s10);
        
        RadioStation s11 = new RadioStation();
        s11.setId("other-2");
        s11.setName("上海新闻广播");
        s11.setStreamUrl("http://lhttp.qingting.fm/live/270/64k.mp3");
        s11.setCountry("China");
        s11.setBitrate(64);
        s11.setLastCheckOk(true);
        s11.setCurrentProgram("上海新闻");
        list.add(s11);
        
        RadioStation s12 = new RadioStation();
        s12.setId("other-3");
        s12.setName("广东新闻广播");
        s12.setStreamUrl("http://lhttp.qingting.fm/live/469/64k.mp3");
        s12.setCountry("China");
        s12.setBitrate(64);
        s12.setLastCheckOk(true);
        s12.setCurrentProgram("广东新闻");
        list.add(s12);
        
        return list;
    }
}
