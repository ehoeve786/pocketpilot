package app.pocketpilot

import android.content.Context
import app.pocketpilot.automation.PlaceAlerts
import app.pocketpilot.automation.PlatformPlaceAlerts
import app.pocketpilot.core.model.PolicyProfile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the oss distribution's [PolicyProfile]; each flavor source set has its own copy. */
@Module
@InstallIn(SingletonComponent::class)
object FlavorModule {
    @Provides
    fun policyProfile(): PolicyProfile = PolicyProfile.OSS

    /** Place triggers use the platform's proximity alerts, since this build ships no Google libraries. */
    @Provides
    @Singleton
    fun placeAlerts(
        @ApplicationContext context: Context,
    ): PlaceAlerts = PlatformPlaceAlerts(context)
}
