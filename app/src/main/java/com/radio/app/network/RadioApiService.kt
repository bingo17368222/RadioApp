package com.radio.app.network

import com.radio.app.models.RadioStation
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class RadioApiService private constructor() {

    companion object {
        private const val BASE_URL = "https://de1.api.radio-browser.info/"
        private var instance: RadioApiService? = null

        @Synchronized
        fun getInstance(): RadioApiService {
            return instance ?: RadioApiService().also { instance = it }
        }
    }

    interface ApiCallback<T> {
        fun onSuccess(result: T)
        fun onError(error: String)
    }

    private val executor: ExecutorService = Executors.newCachedThreadPool()

    fun getAllStations(callback: ApiCallback<List<RadioStation>>) {
        executor.execute {
            val allStations = mutableListOf<RadioStation>()
            val builtin = getBuiltinStations()

            // 添加内置电台（不依赖网络，立即显示）
            allStations.addAll(builtin)

            // 先返回内置电台，让用户立即看到内容
            if (allStations.isNotEmpty()) {
                callback.onSuccess(allStations)
            }

            // 从API获取中文电台（后台补充）
            try {
                val url = URL("${BASE_URL}json/stations/bylanguage/chinese?limit=50&offset=0")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "RadioApp/1.0")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val sb = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) sb.append(line)
                    reader.close()

                    val array = JSONArray(sb.toString())
                    for (i in 0 until array.length()) {
                        val s = RadioStation.fromJson(array.getJSONObject(i))
                        if (s.lastCheckOk) {
                            s.currentProgram = "Live Stream"
                            allStations.add(s)
                        }
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 从API获取河南电台
            try {
                val url = URL("${BASE_URL}json/stations/search?country=China&state=Henan&limit=30")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "RadioApp/1.0")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val sb = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) sb.append(line)
                    reader.close()

                    val array = JSONArray(sb.toString())
                    for (i in 0 until array.length()) {
                        val s = RadioStation.fromJson(array.getJSONObject(i))
                        if (s.lastCheckOk) {
                            s.currentProgram = "河南电台"
                            allStations.add(s)
                        }
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 网络请求完成后再次回调（更新完整列表）
            if (allStations.size > builtin.size) {
                callback.onSuccess(allStations)
            }
        }
    }

    private fun getBuiltinStations(): List<RadioStation> {
        val list = mutableListOf<RadioStation>()

        // === 河南人民广播电台（来自 hndt.com） ===
        list.add(RadioStation().apply {
            id = "henan-news"
            name = "河南新闻广播 FM95.5"
            streamUrl = "https://stream.hndt.com/live/xinwen/playlist.m3u8"
            country = "China"
            bitrate = 64
            lastCheckOk = true
            currentProgram = "河南新闻广播"
        })

        list.add(RadioStation().apply {
            id = "henan-economy"
            name = "河南经济广播 FM103.2"
            streamUrl = "https://stream.hndt.com/live/jingji/playlist.m3u8"
            country = "China"
            bitrate = 64
            lastCheckOk = true
            currentProgram = "河南经济广播"
        })

        list.add(RadioStation().apply {
            id = "henan-traffic"
            name = "河南交通广播 FM104.1"
            streamUrl = "https://stream.hndt.com/live/jiaotong/playlist.m3u8"
            country = "China"
            bitrate = 64
            lastCheckOk = true
            currentProgram = "河南交通广播"
        })

        list.add(RadioStation().apply {
            id = "henan-opera"
            name = "河南戏曲广播 FM97.6"
            streamUrl = "https://stream.hndt.com/live/yule/playlist.m3u8"
            country = "China"
            bitrate = 64
            lastCheckOk = true
            currentProgram = "河南戏曲广播"
        })

        list.add(RadioStation().apply {
            id = "henan-music"
            name = "河南音乐广播 FM88.1"
            streamUrl = "https://stream.hndt.com/live/yinyue/playlist.m3u8"
            country = "China"
            bitrate = 64
            lastCheckOk = true
            currentProgram = "魅力881"
        })

        list.add(RadioStation().apply {
            id = "henan-rural"
            name = "大象资讯台 FM107.4"
            streamUrl = "https://stream.hndt.com/live/nongcun/playlist.m3u8"
            country = "China"
            bitrate = 64
            lastCheckOk = true
            currentProgram = "大象资讯台"
        })

        list.add(RadioStation().apply {
            id = "henan-myradio"
            name = "My Radio FM90.0"
            streamUrl = "https://stream.hndt.com/live/yingshi/playlist.m3u8"
            country = "China"
            bitrate = 64
            lastCheckOk = true
            currentProgram = "My Radio"
        })

        list.add(RadioStation().apply {
            id = "henan-private-car"
            name = "私家车999 FM99.9"
            streamUrl = "https://stream.hndt.com/live/sijiache/playlist.m3u8"
            country = "China"
            bitrate = 64
            lastCheckOk = true
            currentProgram = "私家车999"
        })

        list.add(RadioStation().apply {
            id = "henan-edu"
            name = "河南教育广播 FM106.6"
            streamUrl = "https://stream.hndt.com/live/jiaoyu/playlist.m3u8"
            country = "China"
            bitrate = 64
            lastCheckOk = true
            currentProgram = "河南教育广播"
        })

        list.add(RadioStation().apply {
            id = "henan-info"
            name = "信息广播 FM105.6"
            streamUrl = "https://stream.hndt.com/live/leling/playlist.m3u8"
            country = "China"
            bitrate = 64
            lastCheckOk = true
            currentProgram = "乐龄1056"
        })

        list.add(RadioStation().apply {
            id = "henan-bigradio"
            name = "Big Radio FM93.6"
            streamUrl = "https://stream.hndt.com/live/gudian/playlist.m3u8"
            country = "China"
            bitrate = 64
            lastCheckOk = true
            currentProgram = "Big Radio"
        })

        // === 央广电台 ===
        list.add(RadioStation().apply {
            id = "cnr-1"
            name = "中国之声"
            streamUrl = "https://ngcdn001.cnr.cn/live/zgzs/index.m3u8"
            country = "China"
            bitrate = 128
            lastCheckOk = true
            currentProgram = "中国之声"
        })

        list.add(RadioStation().apply {
            id = "cnr-2"
            name = "经济之声"
            streamUrl = "https://ngcdn002.cnr.cn/live/jjzs/index.m3u8"
            country = "China"
            bitrate = 128
            lastCheckOk = true
            currentProgram = "经济之声"
        })

        list.add(RadioStation().apply {
            id = "cnr-3"
            name = "音乐之声"
            streamUrl = "http://live.xmcdn.com/live/95/64.m3u8"
            country = "China"
            bitrate = 128
            lastCheckOk = true
            currentProgram = "音乐之声"
        })

        return list
    }
}