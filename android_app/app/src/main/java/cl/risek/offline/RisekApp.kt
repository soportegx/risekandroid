package cl.risek.offline

import android.app.Application
import androidx.work.Configuration

class RisekApp : Application(), Configuration.Provider {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()
}
