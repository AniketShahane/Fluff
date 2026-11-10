package app.fluff

import android.app.Application
import app.fluff.work.SyncWorker

class FluffApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SyncWorker.schedule(this)
    }
}
