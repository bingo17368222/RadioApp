package com.radio.app.network

class RetrofitClient private constructor() {

    companion object {
        private var instance: RetrofitClient? = null

        @Synchronized
        fun getInstance(): RetrofitClient {
            return instance ?: RetrofitClient().also { instance = it }
        }
    }

    fun getApiService(): RadioApiService = RadioApiService.getInstance()

    fun getEpisodeApiService(): EpisodeApiService = EpisodeApiService.getInstance()
}
