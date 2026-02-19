package sschoi.docdog.viewer

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import java.security.MessageDigest

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var btnSelectPpt: Button
    private var selectedExpire: ExpireOption = ExpireOption.SEVEN_DAYS

    private val openPptLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let { checkAndUpload(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        btnSelectPpt = findViewById(R.id.btnSelectPpt)

        // 익명 인증 및 만료 파일 정리
        signInAnonymously()

        btnSelectPpt.setOnClickListener {
            selectTTLOptionAndOpenPicker()
        }
    }

    private fun signInAnonymously() {
        FirebaseAuth.getInstance().signInAnonymously()
            .addOnSuccessListener {
                Log.d("DocDog", "signInAnonymously:success")
                cleanupExpiredFilesFromFirestore()
            }
            .addOnFailureListener { e ->
                Log.e("DocDog", "signInAnonymously:failure", e)
                Toast.makeText(this, "인증 실패: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    /** TTL 선택 후 PPT 선택 */
    private fun selectTTLOptionAndOpenPicker() {
        val options = ExpireOption.values().map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("파일 만료 시간 선택")
            .setItems(options) { _, which ->
                selectedExpire = ExpireOption.values()[which]
                openPptLauncher.launch(
                    arrayOf(
                        "application/vnd.ms-powerpoint",
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                    )
                )
            }.show()
    }

    /** PPT 업로드 전 중복 체크 */
    private fun checkAndUpload(uri: Uri) {
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null) {
            Toast.makeText(this, "인증 중입니다. 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
            signInAnonymously()
            return
        }

        val uid = currentUser.uid
        val fileHash = getFileHash(uri)
        val filesRef = FirebaseFirestore.getInstance()
            .collection("userPpt/$uid/files")

        filesRef.whereEqualTo("fileHash", fileHash)
            .get()
            .addOnSuccessListener { snapshot ->
                if (!snapshot.isEmpty) {
                    Toast.makeText(this, "동일 파일은 이미 업로드되었습니다.", Toast.LENGTH_SHORT).show()
                    val firstDoc = snapshot.documents[0]
                    val filePath = firstDoc.getString("filePath")
                    if (filePath != null) {
                         Firebase.storage.reference.child(filePath).downloadUrl.addOnSuccessListener { downloadUri ->
                             openInWebView(downloadUri.toString())
                         }
                    }
                    return@addOnSuccessListener
                }

                // 중복 없으면 업로드 진행
                uploadPptWithFirestore(uri, selectedExpire, fileHash, uid)
            }
            .addOnFailureListener { e ->
                Log.e("DocDog", "Firestore query failure", e)
                Toast.makeText(this, "데이터 확인 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    /** SHA-256 해시 계산 */
    private fun getFileHash(uri: Uri): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(4096)
                var read: Int
                while (input.read(buffer).also { read = it } > 0) {
                    md.update(buffer, 0, read)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e("DocDog", "Hash calculation failed", e)
            ""
        }
    }

    /** 업로드 + Firestore 기록 + WebView 열기 */
    private fun uploadPptWithFirestore(uri: Uri, expire: ExpireOption, fileHash: String, uid: String) {
        val fileName = "ppt_${System.currentTimeMillis()}.pptx"
        val ref = Firebase.storage.reference.child("ppt/$uid/$fileName")

        ref.putFile(uri)
            .addOnSuccessListener {
                ref.downloadUrl.addOnSuccessListener { downloadUri ->
                    val fileData = hashMapOf(
                        "filePath" to "ppt/$uid/$fileName",
                        "createdAt" to System.currentTimeMillis(),
                        "expireMillis" to expire.millis,
                        "fileHash" to fileHash
                    )

                    FirebaseFirestore.getInstance()
                        .collection("userPpt/$uid/files")
                        .add(fileData)

                    openInWebView(downloadUri.toString())
                }
            }
            .addOnFailureListener { e ->
                Log.e("DocDog", "Upload failed", e)
                Toast.makeText(this, "업로드 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    /** Google Docs Viewer WebView 열기 */
    private fun openInWebView(fileUrl: String) {
        val gviewUrl =
            "https://docs.google.com/gview?embedded=true&url=" +
                    java.net.URLEncoder.encode(fileUrl, "UTF-8")

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }

        webView.loadUrl(gviewUrl)
    }

    /** 앱 시작 시 TTL 체크 후 만료 파일 삭제 */
    private fun cleanupExpiredFilesFromFirestore() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val now = System.currentTimeMillis()
        val filesRef = FirebaseFirestore.getInstance().collection("userPpt/$uid/files")

        filesRef.get().addOnSuccessListener { snapshot ->
            snapshot.documents.forEach { doc ->
                val createdAt = doc.getLong("createdAt") ?: return@forEach
                val expireMillis = doc.getLong("expireMillis") ?: return@forEach
                if (expireMillis == 0L) return@forEach // 영구 파일

                if (now > createdAt + expireMillis) {
                    val path = doc.getString("filePath") ?: return@forEach
                    Firebase.storage.reference.child(path).delete()
                    doc.reference.delete()
                }
            }
        }
    }
}

/** TTL 옵션 */
enum class ExpireOption(val label: String, val millis: Long) {
    ONE_HOUR("1시간", 1 * 60 * 60 * 1000),
    SEVEN_DAYS("7일", 7 * 24 * 60 * 60 * 1000),
    THIRTY_DAYS("30일", 30L * 24 * 60 * 60 * 1000),
    PERMANENT("영구", 0)
}
