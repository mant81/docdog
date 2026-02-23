package sschoi.docdog.viewer

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.ads.AdRequest
import android.widget.ImageView
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sschoi.docdog.viewer.data.ExpireOption
import sschoi.docdog.viewer.data.HistoryItem
import sschoi.docdog.viewer.ui.HistoryAdapter
import sschoi.docdog.viewer.ui.MainViewModel
import sschoi.docdog.viewer.util.NetworkUtils
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private lateinit var tabLayout: TabLayout
    private lateinit var layoutDocument: LinearLayout
    private lateinit var layoutHistory: LinearLayout
    private lateinit var layoutAbout: View
    private lateinit var layoutLoading: LinearLayout
    private lateinit var layoutIntro: View
    private lateinit var layoutMainContent: LinearLayout
    private lateinit var tvLoadingMessage: TextView
    private lateinit var adView: AdView
    
    private lateinit var webView: WebView
    private lateinit var btnSelectPpt: Button
    private lateinit var btnClearHistory: Button
    private lateinit var historyRecyclerView: RecyclerView
    private lateinit var historyAdapter: HistoryAdapter

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
            uri?.let { 
                val fileName = getFileName(it) ?: "file"
                viewModel.uploadToSupabase(this, it, fileName) 
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // AdMob 초기화
        MobileAds.initialize(this) {}

        initViews()
        setupWebView()
        setupTabs()
        setupRecyclerView()
        observeViewModel()

        viewModel.loadHistory(this)
        
        if (!NetworkUtils.isNetworkAvailable(this)) {
            Toast.makeText(this, "인터넷 연결이 원활하지 않습니다. 앱 사용에 제한이 있을 수 있습니다.", Toast.LENGTH_LONG).show()
        }

        btnSelectPpt.setOnClickListener {
            openPptLauncher.launch(mimeTypes)
        }

        btnClearHistory.setOnClickListener {
            showClearHistoryDialog()
        }

        // 광고 로드
        val adRequest = AdRequest.Builder().build()
        adView.loadAd(adRequest)

        // 인트로 시퀀스 시작
        startIntroSequence()
    }

    private fun initViews() {
        tabLayout = findViewById(R.id.tabLayout)
        layoutDocument = findViewById(R.id.layoutDocument)
        layoutHistory = findViewById(R.id.layoutHistory)
        layoutAbout = findViewById(R.id.layoutAbout)
        layoutLoading = findViewById(R.id.layoutLoading)
        layoutIntro = findViewById(R.id.layoutIntro)
        layoutMainContent = findViewById(R.id.layoutMainContent)
        tvLoadingMessage = findViewById(R.id.tvLoadingMessage)
        adView = findViewById(R.id.adView)
        
        webView = findViewById(R.id.webView)
        btnSelectPpt = findViewById(R.id.btnSelectPpt)
        btnClearHistory = findViewById(R.id.btnClearHistory)
        historyRecyclerView = findViewById(R.id.historyRecyclerView)
    }

    private fun startIntroSequence() {
        lifecycleScope.launch {
            // 1. 2초 동안 인트로만 완벽하게 노출 (메인은 GONE 상태)
            delay(2000)
            
            // 2. 메인 컨텐츠를 보이게 설정
            layoutMainContent.visibility = View.VISIBLE
            layoutMainContent.alpha = 1f
            
            // 3. 인트로 화면을 페이드 아웃하며 제거
            layoutIntro.animate()
                .alpha(0f)
                .setDuration(400)
                .withEndAction {
                    layoutIntro.visibility = View.GONE
                }
        }
    }

    private fun setupRecyclerView() {
        historyAdapter = HistoryAdapter(
            items = emptyList(),
            onItemClick = { item ->
                val expiryTimeMillis = item.createdAt + item.expireOption.duration.inWholeMilliseconds
                if (System.currentTimeMillis() > expiryTimeMillis) {
                    Toast.makeText(this, "해당 문서는 만료되어 더 이상 조회할 수 없습니다.", Toast.LENGTH_SHORT).show()
                } else {
                    tabLayout.getTabAt(0)?.select()
                    viewModel.createSignedUrlAndOpen(this, item.storagePath, item.expireOption)
                }
            },
            onDeleteClick = { item ->
                showDeleteConfirmDialog(item)
            }
        )
        historyRecyclerView.layoutManager = LinearLayoutManager(this)
        historyRecyclerView.adapter = historyAdapter
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.historyList.collect { list ->
                        historyAdapter.updateData(list)
                    }
                }
                launch {
                    viewModel.isLoading.collect { isLoading ->
                        layoutLoading.visibility = if (isLoading) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.loadingMessage.collect { message ->
                        tvLoadingMessage.text = message
                    }
                }
                launch {
                    viewModel.uiEvent.collect { event ->
                        when (event) {
                            is MainViewModel.UiEvent.ShowToast -> {
                                Toast.makeText(this@MainActivity, event.message, Toast.LENGTH_LONG).show()
                            }
                            is MainViewModel.UiEvent.ShowExpireDialog -> {
                                showExpireOptionDialog(event.originalFileName, event.storagePath)
                            }
                            is MainViewModel.UiEvent.OpenUrl -> {
                                openInWebView(event.url)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showDeleteConfirmDialog(item: HistoryItem) {
        AlertDialog.Builder(this)
            .setTitle("내역 삭제")
            .setMessage("'${item.fileName}' 내역을 삭제하시겠습니까?")
            .setPositiveButton("삭제") { _, _ ->
                viewModel.deleteHistoryItem(this, item)
                Toast.makeText(this, "삭제되었습니다.", Toast.LENGTH_SHORT).show()
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
                tvLoadingMessage.text = "문서를 불러오는 중입니다..."
                layoutLoading.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                layoutLoading.visibility = View.GONE
                // 페이지 로딩이 완료되면 WebView를 보이게 함
                webView.visibility = View.VISIBLE
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                layoutLoading.visibility = View.GONE
                Log.e("DocDog", "WebView Error: ${error?.description}")
            }
        }
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

    private fun showClearHistoryDialog() {
        if (viewModel.historyList.value.isEmpty()) {
            Toast.makeText(this, "삭제할 내역이 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("전체 삭제")
            .setMessage("모든 변환 내역을 삭제하시겠습니까?")
            .setPositiveButton("삭제") { _, _ ->
                viewModel.clearHistory(this)
                Toast.makeText(this, "모든 내역이 삭제되었습니다.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showExpireOptionDialog(originalFileName: String, storagePath: String) {
        val options = ExpireOption.entries.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("파일 만료 시간 선택")
            .setCancelable(false)
            .setItems(options) { _, which ->
                val selectedOption = ExpireOption.entries[which]
                val newItem = HistoryItem(originalFileName, storagePath, selectedOption)
                viewModel.addHistoryItem(this, newItem)
                viewModel.createSignedUrlAndOpen(this, storagePath, selectedOption)
            }.show()
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
        val gviewUrl = "https://docs.google.com/gview?embedded=true&url=" + URLEncoder.encode(fileUrl, "UTF-8")
        webView.loadUrl(gviewUrl)
    }

    override fun onDestroy() {
        adView.destroy()
        webView.destroy()
        super.onDestroy()
    }

    override fun onPause() {
        adView.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        adView.resume()
    }
}
