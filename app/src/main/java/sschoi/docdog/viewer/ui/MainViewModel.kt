package sschoi.docdog.viewer.ui

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import sschoi.docdog.viewer.data.ExpireOption
import sschoi.docdog.viewer.data.HistoryItem
import sschoi.docdog.viewer.util.NetworkUtils
import sschoi.docdog.viewer.util.SupabaseProvider
import java.util.UUID

class MainViewModel : ViewModel() {

    private val _historyList = MutableStateFlow<List<HistoryItem>>(emptyList())
    val historyList: StateFlow<List<HistoryItem>> = _historyList.asStateFlow()

    private val _uiEvent = MutableSharedFlow<UiEvent>()
    val uiEvent: SharedFlow<UiEvent> = _uiEvent.asSharedFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadingMessage = MutableStateFlow("")
    val loadingMessage: StateFlow<String> = _loadingMessage.asStateFlow()

    fun loadHistory(context: Context) {
        val sharedPrefs = context.getSharedPreferences("docdog_prefs", Context.MODE_PRIVATE)
        val jsonString = sharedPrefs.getString("upload_history", null)
        if (jsonString != null) {
            try {
                val savedHistory = Json.decodeFromString<List<HistoryItem>>(jsonString)
                _historyList.value = savedHistory
            } catch (e: Exception) {
                Log.e("DocDog", "History Load Error", e)
            }
        }
    }

    private fun saveHistory(context: Context) {
        val sharedPrefs = context.getSharedPreferences("docdog_prefs", Context.MODE_PRIVATE)
        try {
            val jsonString = Json.encodeToString(_historyList.value)
            sharedPrefs.edit().putString("upload_history", jsonString).apply()
        } catch (e: Exception) {
            Log.e("DocDog", "History Save Error", e)
        }
    }

    fun uploadToSupabase(context: Context, uri: Uri, originalFileName: String) {
        if (!NetworkUtils.isNetworkAvailable(context)) {
            viewModelScope.launch {
                _uiEvent.emit(UiEvent.ShowToast("인터넷 연결이 원활하지 않습니다. 네트워크 설정을 확인해주세요."))
            }
            return
        }

        viewModelScope.launch {
            try {
                _loadingMessage.value = "문서를 업로드 중입니다..."
                _isLoading.value = true
                
                _uiEvent.emit(UiEvent.ShowToast("보안 정책에 따라 문서를 안전하게 처리 중입니다. 데이터는 설정된 기간 이후 자동으로 파기됩니다."))

                val extension = originalFileName.substringAfterLast('.', "")
                val uuid = UUID.randomUUID().toString()
                val fileName = if (extension.isNotEmpty()) "$uuid.$extension" else uuid

                val fileBytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: return@launch

                val bucket = SupabaseProvider.client.storage.from("docs")
                bucket.upload(fileName, fileBytes) { upsert = true }

                _isLoading.value = false
                _uiEvent.emit(UiEvent.ShowExpireDialog(originalFileName, fileName))

            } catch (e: Exception) {
                Log.e("DocDog", "Supabase Error: ${e.message}", e)
                _isLoading.value = false
                _uiEvent.emit(UiEvent.ShowToast("오류 발생: ${e.localizedMessage}"))
            }
        }
    }

    fun addHistoryItem(context: Context, item: HistoryItem) {
        val currentList = _historyList.value.toMutableList()
        currentList.add(0, item)
        _historyList.value = currentList
        saveHistory(context)
    }

    fun deleteHistoryItem(context: Context, item: HistoryItem) {
        val currentList = _historyList.value.toMutableList()
        if (currentList.remove(item)) {
            _historyList.value = currentList
            saveHistory(context)
        }
    }

    fun clearHistory(context: Context) {
        _historyList.value = emptyList()
        saveHistory(context)
    }

    fun createSignedUrlAndOpen(context: Context, storagePath: String, expireOption: ExpireOption) {
        if (!NetworkUtils.isNetworkAvailable(context)) {
            viewModelScope.launch {
                _uiEvent.emit(UiEvent.ShowToast("인터넷 연결이 원활하지 않습니다. 네트워크 설정을 확인해주세요."))
            }
            return
        }

        viewModelScope.launch {
            try {
                _loadingMessage.value = "문서를 불러오는 중입니다..."
                _isLoading.value = true

                val bucket = SupabaseProvider.client.storage.from("docs")
                val signedUrl = bucket.createSignedUrl(
                    path = storagePath,
                    expiresIn = expireOption.duration
                )
                _isLoading.value = false
                _uiEvent.emit(UiEvent.OpenUrl(signedUrl))
            } catch (e: Exception) {
                Log.e("DocDog", "Signed URL Error: ${e.message}", e)
                _isLoading.value = false
                _uiEvent.emit(UiEvent.ShowToast("URL 생성 실패: ${e.localizedMessage}"))
            }
        }
    }

    sealed class UiEvent {
        data class ShowToast(val message: String) : UiEvent()
        data class ShowExpireDialog(val originalFileName: String, val storagePath: String) : UiEvent()
        data class OpenUrl(val url: String) : UiEvent()
    }
}
