package cl.risek.offline

import android.content.Context

class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("risek_session", Context.MODE_PRIVATE)
    fun save(token: String, vendedorCodigo: String?) = prefs.edit().putString("token", token).putString("vendedor", vendedorCodigo).apply()
    fun clearLogin() = prefs.edit().remove("token").remove("vendedor").apply()
    fun token(): String? = prefs.getString("token", null)
    fun authHeader(): String = "Bearer ${token().orEmpty()}"
    fun vendedorCodigo(): String? = prefs.getString("vendedor", null)
    fun bootstrapSignature(key: String): String? = prefs.getString("bootstrap_sig_$key", null)
    fun saveBootstrapSignature(key: String, value: String?) {
        if (!value.isNullOrBlank()) prefs.edit().putString("bootstrap_sig_$key", value).apply()
    }
    fun lastSuccessfulSyncDate(): String? = prefs.getString("last_successful_sync_date", null)
    fun lastSuccessfulSyncAt(): String? = prefs.getString("last_successful_sync_at", null)
    fun saveSuccessfulSync(date: String, displayValue: String) {
        prefs.edit()
            .putString("last_successful_sync_date", date)
            .putString("last_successful_sync_at", displayValue)
            .apply()
    }
    fun lastSyncReminderDate(): String? = prefs.getString("last_sync_reminder_date", null)
    fun saveSyncReminderDate(date: String) {
        prefs.edit().putString("last_sync_reminder_date", date).apply()
    }
    fun lastFechaReparto(): String? = prefs.getString("last_fecha_reparto", null)
    fun saveLastFechaReparto(fecha: String) {
        prefs.edit().putString("last_fecha_reparto", fecha).apply()
    }
}
