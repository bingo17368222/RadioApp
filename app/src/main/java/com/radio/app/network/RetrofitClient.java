package com.radio.app.network;

public class RetrofitClient {
    private static RetrofitClient instance;

    private RetrofitClient() {}

    public static synchronized RetrofitClient getInstance() {
        if (instance == null) instance = new RetrofitClient();
        return instance;
    }

    public RadioApiService getApiService() {
        return RadioApiService.getInstance();
    }

    public EpisodeApiService getEpisodeApiService() {
        return EpisodeApiService.getInstance();
    }
}
