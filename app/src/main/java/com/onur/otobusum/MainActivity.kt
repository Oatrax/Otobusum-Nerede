package com.onur.otobusum

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.onur.otobusum.data.Favorites
import com.onur.otobusum.ui.AppTheme
import com.onur.otobusum.ui.HomeScreen
import com.onur.otobusum.ui.LineMapScreen
import com.onur.otobusum.ui.LineScreen
import com.onur.otobusum.ui.StopScreen
import android.net.Uri

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val favorites = Favorites(applicationContext)
        setContent {
            AppTheme {
                val nav = rememberNavController()
                fun enc(s: String) = Uri.encode(s)
                NavHost(nav, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            favorites = favorites,
                            openStop = { code, name -> nav.navigate("stop/${enc(code)}?name=${enc(name)}") },
                            openLine = { code, name -> nav.navigate("line/${enc(code)}?name=${enc(name)}") },
                        )
                    }
                    composable(
                        "stop/{code}?name={name}",
                        arguments = listOf(navArgument("code") { type = NavType.StringType },
                            navArgument("name") { type = NavType.StringType; defaultValue = "" }),
                    ) { e ->
                        StopScreen(
                            code = e.arguments!!.getString("code")!!,
                            initialName = e.arguments!!.getString("name").orEmpty(),
                            favorites = favorites,
                            back = { nav.popBackStack() },
                            openLine = { code, name -> nav.navigate("line/${enc(code)}?name=${enc(name)}") },
                            openMap = { line, stop -> nav.navigate("map/${enc(line)}?stop=${enc(stop)}") },
                        )
                    }
                    composable(
                        "map/{code}?stop={stop}&name={name}",
                        arguments = listOf(navArgument("code") { type = NavType.StringType },
                            navArgument("stop") { type = NavType.StringType; defaultValue = "" },
                            navArgument("name") { type = NavType.StringType; defaultValue = "" }),
                    ) { e ->
                        val line = e.arguments!!.getString("code")!!
                        val name = e.arguments!!.getString("name").orEmpty()
                        LineMapScreen(
                            line = line,
                            name = name,
                            focusStop = e.arguments!!.getString("stop")?.ifBlank { null },
                            back = { nav.popBackStack() },
                            openStops = { nav.navigate("line/${enc(line)}?name=${enc(name)}") },
                        )
                    }
                    composable(
                        "line/{code}?name={name}",
                        arguments = listOf(navArgument("code") { type = NavType.StringType },
                            navArgument("name") { type = NavType.StringType; defaultValue = "" }),
                    ) { e ->
                        LineScreen(
                            code = e.arguments!!.getString("code")!!,
                            name = e.arguments!!.getString("name").orEmpty(),
                            favorites = favorites,
                            back = { nav.popBackStack() },
                            openStop = { code, name -> nav.navigate("stop/${enc(code)}?name=${enc(name)}") },
                            openMap = { line -> nav.navigate("map/${enc(line)}?name=${enc(e.arguments!!.getString("name").orEmpty())}") },
                        )
                    }
                }
            }
        }
    }
}
