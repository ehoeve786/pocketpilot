package app.pocketpilot

import android.content.Context
import app.pocketpilot.automation.GmsPlaceAlerts
import app.pocketpilot.automation.PlaceAlerts
import app.pocketpilot.core.model.PolicyProfile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the play distribution's [PolicyProfile]; each flavor source set has its own copy. */
@Module
@InstallIn(SingletonComponent::class)
object FlavorModule {
    @Provides
    fun policyProfile(): PolicyProfile = PolicyProfile.PLAY

    /** Place triggers use Google's geofencing, falling back to the platform's alerts on phones without Play services. */
    @Provides
    @Singleton
    fun placeAlerts(
        @ApplicationContext context: Context,
    ): PlaceAlerts = GmsPlaceAlerts(context)
}
