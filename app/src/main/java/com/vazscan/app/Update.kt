package com.vazscan.app

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun UpdateCard() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("vazscan", Context.MODE_PRIVATE) }
    var token by remember { mutableStateOf(prefs.getString("gh_token", "") ?: "") }
    var status by remember { mutableStateOf("Текущая версия: ${BuildConfig.VERSION_NAME}") }
    var info by remember { mutableStateOf<UpdateInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Обновление приложения", style = MaterialTheme.typography.titleMedium)
            Text(status)
            if (busy) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        status = "Проверка обновлений…"
                        try {
                            val u = withContext(Dispatchers.IO) { Updater.latest(token.ifBlank { null }) }
                            when {
                                u == null -> status = "Не удалось определить версию последнего релиза"
                                u.code <= BuildConfig.VERSION_CODE -> {
                                    info = null
                                    status = "Установлена последняя версия (${BuildConfig.VERSION_NAME})"
                                }
                                else -> {
                                    info = u
                                    status = "Доступна новая версия ${u.tag}"
                                }
                            }
                        } catch (e: Exception) {
                            status = "Ошибка проверки: ${e.message}"
                        } finally {
                            busy = false
                        }
                    }
                }
            ) { Text("Проверить обновления") }

            info?.let { u ->
                Spacer(Modifier.height(6.dp))
                Button(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            progress = 0f
                            status = "Загрузка ${u.tag}…"
                            try {
                                val f = withContext(Dispatchers.IO) {
                                    Updater.download(u, token.ifBlank { null }, ctx.cacheDir) { progress = it }
                                }
                                status = "Загружено. Подтвердите установку."
                                Updater.install(ctx, f)
                            } catch (e: Exception) {
                                status = "Ошибка загрузки: ${e.message}"
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) { Text("Скачать и установить ${u.tag}") }
            }

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = token,
                onValueChange = {
                    token = it
                    prefs.edit().putString("gh_token", it.trim()).apply()
                },
                label = { Text("Токен GitHub (нужен, если репозиторий приватный)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
