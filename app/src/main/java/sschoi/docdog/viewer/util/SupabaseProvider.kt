package sschoi.docdog.viewer.util

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.storage.Storage
import sschoi.docdog.viewer.BuildConfig

/**
 * Supabase 클라이언트 인스턴스를 제공하는 프로바이더.
 * local.properties의 설정을 기반으로 싱글톤 인스턴스를 관리합니다.
 */
object SupabaseProvider {
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
