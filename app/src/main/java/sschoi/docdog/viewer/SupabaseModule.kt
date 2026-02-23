package sschoi.docdog.viewer

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.storage.Storage

/**
 * Supabase 설정을 관리하는 모듈
 * 
 * 보안을 위해 SUPABASE_URL과 SUPABASE_KEY는 local.properties에 정의되어 있으며,
 * build.gradle을 통해 BuildConfig로 주입됩니다.
 */
object SupabaseModule {
    // BuildConfig를 통해 주입된 값을 사용 (import sschoi.docdog.viewer.BuildConfig 자동 연결)
    private val SUPABASE_URL = BuildConfig.SUPABASE_URL
    private val SUPABASE_KEY = BuildConfig.SUPABASE_KEY

    val client: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = SUPABASE_URL,
            supabaseKey = SUPABASE_KEY
        ) {
            install(Auth)
            install(Storage)
        }
    }
}
