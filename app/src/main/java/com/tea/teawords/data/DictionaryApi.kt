package com.tea.teawords.data

import android.text.Html
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder

class DictionaryApi {

    private val client = OkHttpClient()
    private val gson = Gson()

    fun lookupWord(word: String, callback: (WordResponse?) -> Unit) {
        val encodedWord = URLEncoder.encode(word.trim(), "UTF-8")
        val url = "https://api.dictionaryapi.dev/api/v2/entries/en/$encodedWord"
        
        val request = Request.Builder()
            .url(url)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("DictionaryApi", "lookupWord failed: ${e.message}", e)
                callback(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val bodyString = response.body?.string()
                if (!response.isSuccessful || bodyString.isNullOrEmpty()) {
                    Log.w("DictionaryApi", "lookupWord error response: ${response.code}")
                    callback(null)
                    return
                }

                try {
                    val listType = object : TypeToken<List<WordResponse>>() {}.type
                    val wordResponses: List<WordResponse> = gson.fromJson(bodyString, listType)
                    callback(wordResponses.firstOrNull())
                } catch (e: Exception) {
                    Log.e("DictionaryApi", "JSON parsing failed for lookupWord: ${e.message}", e)
                    callback(null)
                }
            }
        })
    }

    fun translate(text: String, callback: (String?) -> Unit) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            callback("")
            return
        }

        val isZh = isChinese(trimmed)
        val langPair = if (isZh) "zh-CN|en" else "en|zh-CN"
        val encodedText = URLEncoder.encode(trimmed, "UTF-8")
        val url = "https://api.mymemory.translated.net/get?q=$encodedText&langpair=$langPair"

        val request = Request.Builder()
            .url(url)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("DictionaryApi", "translate failed: ${e.message}", e)
                callback(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val bodyString = response.body?.string()
                if (!response.isSuccessful || bodyString.isNullOrEmpty()) {
                    Log.w("DictionaryApi", "translate error response: ${response.code}")
                    callback(null)
                    return
                }

                try {
                    val myMemoryResponse = gson.fromJson(bodyString, MyMemoryResponse::class.java)
                    callback(myMemoryResponse.responseData?.translatedText?.decodeHtmlEntities())
                } catch (e: Exception) {
                    Log.e("DictionaryApi", "JSON parsing failed for translate: ${e.message}", e)
                    callback(null)
                }
            }
        })
    }

    private fun String.decodeHtmlEntities(): String {
        return Html.fromHtml(this, Html.FROM_HTML_MODE_LEGACY).toString()
    }

    private fun isChinese(text: String): Boolean {
        for (char in text) {
            if (Character.UnicodeBlock.of(char) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) {
                return true
            }
        }
        return false
    }
}
