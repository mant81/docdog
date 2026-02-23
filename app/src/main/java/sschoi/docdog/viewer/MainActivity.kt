package sschoi.docdog.viewer

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class MainActivity : AppCompatActivity() {

    private lateinit var tabLayout: TabLayout
    private lateinit var layoutDocument: LinearLayout
    private lateinit var layoutHistory: LinearLayout
    private lateinit var layoutAbout: View
    private lateinit var layoutLoading: LinearLayout
    private lateinit var tvLoadingMessage: TextView
    
    private lateinit var webView: WebView
    private lateinit var btnSelectPpt: Button
    private lateinit var btnClearHistory: Button
    private lateinit var historyRecyclerView: RecyclerView
    private lateinit var historyAdapter: HistoryAdapter
    private val historyList = mutableListOf<HistoryItem>()

    private val sharedPrefs by lazy { getSharedPreferences("docdog_prefs", Context.MODE_PRIVATE) }

    private val mimeTypes = arrayOf(
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/pdf",
        "text/plain"
    )

    private val openPptLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let { uploadToSupabase(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tabLayout = findViewById(R.id.tabLayout)
        layoutDocument = findViewById(R.id.layoutDocument)
        layoutHistory = findViewById(R.id.layoutHistory)
        layoutAbout = findViewById(R.id.layoutAbout)
        layoutLoading = findViewById(R.id.layoutLoading)
        tvLoadingMessage = findViewById(R.id.tvLoadingMessage)
        
        webView = findViewById(R.id.webView)
        btnSelectPpt = findViewById(R.id.btnSelectPpt)
        btnClearHistory = findViewById(R.id.btnClearHistory)
        historyRecyclerView = findViewById(R.id.historyRecyclerView)

        setupWebView()
        setupTabs()
        loadHistory()

        historyAdapter = HistoryAdapter(
            items = historyList,
            onItemClick = { item ->
                val expiryTimeMillis = item.createdAt + item.expireOption.duration.inWholeMilliseconds
                if (System.currentTimeMillis() > expiryTimeMillis) {
                    Toast.makeText(this, "해당 문서는 만료되어 더 이상 조회할 수 없습니다.", Toast.LENGTH_SHORT).show()
                } else {
                    tabLayout.getTabAt(0)?.select()
                    createSignedUrlAndOpen(item.storagePath, item.expireOption)
                }
            },
            onDeleteClick = { item ->
                showDeleteConfirmDialog(item)
            }
        )
        historyRecyclerView.layoutManager = LinearLayoutManager(this)
        historyRecyclerView.adapter = historyAdapter

        btnSelectPpt.setOnClickListener {
            openPptLauncher.launch(mimeTypes)
        }

        btnClearHistory.setOnClickListener {
            showClearHistoryDialog()
        }
    }

    private fun showDeleteConfirmDialog(item: HistoryItem) {
        AlertDialog.Builder(this)
            .setTitle("내역 삭제")
            .setMessage("'${item.fileName}' 내역을 삭제하시겠습니까?")
            .setPositiveButton("삭제") { _, _ ->
                val position = historyList.indexOf(item)
                if (position != -1) {
                    historyList.removeAt(position)
                    historyAdapter.notifyItemRemoved(position)
                    saveHistory()
                    Toast.makeText(this, "삭제되었습니다.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                showLoading("문서를 불러오는 중입니다...")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                hideLoading()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                hideLoading()
                Log.e("DocDog", "WebView Error: ${error?.description}")
            }
        }
    }

    private fun showLoading(message: String) {
        if (isFinishing || isDestroyed) return
        tvLoadingMessage.text = message
        layoutLoading.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        if (isFinishing || isDestroyed) return
        layoutLoading.visibility = View.GONE
    }

    private fun setupTabs() {
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        layoutDocument.visibility = View.VISIBLE
                        layoutHistory.visibility = View.GONE
                        layoutAbout.visibility = View.GONE
                    }
                    1 -> {
                        layoutDocument.visibility = View.GONE
                        layoutHistory.visibility = View.VISIBLE
                        layoutAbout.visibility = View.GONE
                        historyAdapter.notifyDataSetChanged()
                    }
                    2 -> {
                        layoutDocument.visibility = View.GONE
                        layoutHistory.visibility = View.GONE
                        layoutAbout.visibility = View.VISIBLE
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun loadHistory() {
        val jsonString = sharedPrefs.getString("upload_history", null)
        if (jsonString != null) {
            try {
                val savedHistory = Json.decodeFromString<List<HistoryItem>>(jsonString)
                historyList.clear()
                historyList.addAll(savedHistory)
            } catch (e: Exception) {
                Log.e("DocDog", "History Load Error", e)
            }
        }
    }

    private fun saveHistory() {
        try {
            val jsonString = Json.encodeToString(historyList)
            sharedPrefs.edit().putString("upload_history", jsonString).apply()
        } catch (e: Exception) {
            Log.e("DocDog", "History Save Error", e)
        }
    }

    private fun showClearHistoryDialog() {
        if (historyList.isEmpty()) {
            Toast.makeText(this, "삭제할 내역이 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("전체 삭제")
            .setMessage("모든 변환 내역을 삭제하시겠습니까?")
            .setPositiveButton("삭제") { _, _ ->
                historyList.clear()
                historyAdapter.notifyDataSetChanged()
                saveHistory()
                Toast.makeText(this, "모든 내역이 삭제되었습니다.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun uploadToSupabase(uri: Uri) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.Main) {
                    showLoading("문서를 업로드 중입니다...")
                    Toast.makeText(this@MainActivity, "보안 정책에 따라 문서를 안전하게 처리 중입니다. 데이터는 설정된 기간 이후 자동으로 파기됩니다.", Toast.LENGTH_LONG).show()
                }

                val originalFileName = getFileName(uri) ?: "file"
                val extension = originalFileName.substringAfterLast('.', "")
                val uuid = UUID.randomUUID().toString()
                val fileName = if (extension.isNotEmpty()) "$uuid.$extension" else uuid

                val fileBytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: return@launch

                val bucket = SupabaseModule.client.storage.from("docs")

                bucket.upload(fileName, fileBytes) { upsert = true }

                withContext(Dispatchers.Main) {
                    hideLoading()
                    showExpireOptionDialog(originalFileName, fileName)
                }

            } catch (e: Exception) {
                Log.e("DocDog", "Supabase Error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    hideLoading()
                    Toast.makeText(this@MainActivity, "오류 발생: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showExpireOptionDialog(originalFileName: String, storagePath: String) {
        if (isFinishing || isDestroyed) return
        
        val options = ExpireOption.entries.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("파일 만료 시간 선택")
            .setCancelable(false)
            .setItems(options) { _, which ->
                val selectedOption = ExpireOption.entries[which]
                
                historyList.add(0, HistoryItem(originalFileName, storagePath, selectedOption))
                historyAdapter.notifyItemInserted(0)
                historyRecyclerView.scrollToPosition(0)
                saveHistory()

                createSignedUrlAndOpen(storagePath, selectedOption)
            }.show()
    }

    private fun createSignedUrlAndOpen(storagePath: String, expireOption: ExpireOption) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.Main) {
                    showLoading("문서를 불러오는 중입니다...")
                }

                val bucket = SupabaseModule.client.storage.from("docs")
                val signedUrl = bucket.createSignedUrl(
                    path = storagePath,
                    expiresIn = expireOption.duration
                )
                withContext(Dispatchers.Main) {
                    openInWebView(signedUrl)
                }
            } catch (e: Exception) {
                Log.e("DocDog", "Signed URL Error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    hideLoading()
                    Toast.makeText(this@MainActivity, "URL 생성 실패: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) result = it.getString(index)
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) result = result?.substring(cut + 1)
        }
        return result
    }

    private fun openInWebView(fileUrl: String) {
        if (isFinishing || isDestroyed) return
        
        val gviewUrl = "https://docs.google.com/gview?embedded=true&url=" + URLEncoder.encode(fileUrl, "UTF-8")
        webView.loadUrl(gviewUrl)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}

@Serializable
data class HistoryItem(
    val fileName: String,
    val storagePath: String,
    val expireOption: ExpireOption,
    val createdAt: Long = System.currentTimeMillis()
)

class HistoryAdapter(
    private val items: List<HistoryItem>,
    private val onItemClick: (HistoryItem) -> Unit,
    private val onDeleteClick: (HistoryItem) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvFileName: TextView = view.findViewById(R.id.tvFileName)
        val tvExpireTime: TextView = view.findViewById(R.id.tvExpireTime)
        val tvStatus: TextView = view.findViewById(R.id.tvStatus)
        val btnView: Button = view.findViewById(R.id.btnView)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvFileName.text = item.fileName
        
        val expiryMillis = item.createdAt + item.expireOption.duration.inWholeMilliseconds
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        holder.tvExpireTime.text = "만료: ${sdf.format(Date(expiryMillis))}"
        
        val isExpired = System.currentTimeMillis() > expiryMillis
        if (isExpired) {
            holder.tvStatus.visibility = View.VISIBLE
            holder.btnView.isEnabled = false
            holder.btnView.alpha = 0.5f
        } else {
            holder.tvStatus.visibility = View.GONE
            holder.btnView.isEnabled = true
            holder.btnView.alpha = 1.0f
        }

        holder.btnView.setOnClickListener { onItemClick(item) }
        holder.btnDelete.setOnClickListener { onDeleteClick(item) }
    }

    override fun getItemCount() = items.size
}

enum class ExpireOption(val label: String, val duration: Duration) {
    ONE_HOUR("1시간", 1.hours),
    SEVEN_DAYS("7일", 7.days),
    THIRTY_DAYS("30일", 30.days)
}
