package com.onur.otobusum.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class Favorite(val kind: SearchResult.Kind, val code: String, val title: String, val subtitle: String)

class Favorites(context: Context) {
    private val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Favorite>> = _items

    private fun load(): List<Favorite> = runCatching {
        val arr = JSONArray(prefs.getString("items", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Favorite(SearchResult.Kind.valueOf(o.getString("kind")), o.getString("code"),
                o.getString("title"), o.optString("subtitle"))
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<Favorite>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("kind", it.kind.name).put("code", it.code)
                .put("title", it.title).put("subtitle", it.subtitle))
        }
        prefs.edit().putString("items", arr.toString()).apply()
        _items.value = list
    }

    fun isFavorite(kind: SearchResult.Kind, code: String) = _items.value.any { it.kind == kind && it.code == code }

    fun toggle(fav: Favorite) {
        val cur = _items.value
        save(if (isFavorite(fav.kind, fav.code)) cur.filterNot { it.kind == fav.kind && it.code == fav.code } else cur + fav)
    }
}
