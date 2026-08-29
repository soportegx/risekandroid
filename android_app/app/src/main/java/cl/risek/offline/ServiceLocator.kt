package cl.risek.offline

import android.content.Context
import androidx.room.Room
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ServiceLocator {
    lateinit var db: RisekDatabase private set
    lateinit var api: RisekApi private set
    lateinit var session: SessionStore private set
    lateinit var authRepository: AuthRepository private set
    lateinit var catalogRepository: CatalogRepository private set
    lateinit var nvRepository: NvRepository private set

    fun init(context: Context) {
        db = Room.databaseBuilder(context, RisekDatabase::class.java, "risek_offline.db").fallbackToDestructiveMigration().build()
        session = SessionStore(context)
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val token = session.token()
                val request = if (token.isNullOrBlank()) {
                    chain.request()
                } else {
                    chain.request().newBuilder().addHeader("Authorization", "Bearer $token").build()
                }
                chain.proceed(request)
            }
            .addInterceptor(logging)
            .build()
        api = Retrofit.Builder().baseUrl(Config.BASE_URL).client(client).addConverterFactory(GsonConverterFactory.create()).build().create(RisekApi::class.java)
        authRepository = AuthRepository(api, db.secUserDao(), session)
        catalogRepository = CatalogRepository(api, db.catalogDao(), db.cuentaCorrienteDao(), session)
        nvRepository = NvRepository(api, db.nvDao(), session)
    }
}
