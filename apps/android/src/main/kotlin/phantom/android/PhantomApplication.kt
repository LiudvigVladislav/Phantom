package phantom.android

import android.app.Application
import phantom.android.di.AppContainer

class PhantomApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
