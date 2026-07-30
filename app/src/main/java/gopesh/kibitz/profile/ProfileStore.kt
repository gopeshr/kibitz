package gopesh.kibitz.profile

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Persists the player's profile.
 *
 * [android.content.SharedPreferences] is the right size of tool for six scalar fields, and
 * every access here is moved off the main thread. Game history and per-move error records
 * arrive in a later phase and want a real database (Room) — that is the point at which this
 * should graduate, not before.
 */
class ProfileStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    suspend fun load(): UserProfile? = withContext(Dispatchers.IO) {
        val name = prefs.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() }
            ?: return@withContext null
        UserProfile(
            name = name,
            ratingLow = prefs.getInt(KEY_RATING_LOW, 0),
            ratingHigh = prefs.getInt(KEY_RATING_HIGH, 0),
            bandLabel = prefs.getString(KEY_BAND, "").orEmpty(),
            averageLoss = prefs.getInt(KEY_AVERAGE_LOSS, 0),
            assessmentsCompleted = prefs.getInt(KEY_ASSESSMENTS, 0),
            assessmentDeclined = prefs.getBoolean(KEY_DECLINED, false),
        )
    }

    suspend fun save(profile: UserProfile) = withContext(Dispatchers.IO) {
        prefs.edit()
            .putString(KEY_NAME, profile.name)
            .putInt(KEY_RATING_LOW, profile.ratingLow)
            .putInt(KEY_RATING_HIGH, profile.ratingHigh)
            .putString(KEY_BAND, profile.bandLabel)
            .putInt(KEY_AVERAGE_LOSS, profile.averageLoss)
            .putInt(KEY_ASSESSMENTS, profile.assessmentsCompleted)
            .putBoolean(KEY_DECLINED, profile.assessmentDeclined)
            .commit()
        Unit
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        prefs.edit().clear().commit()
        Unit
    }

    private companion object {
        const val FILE_NAME = "kibitz_profile"
        const val KEY_NAME = "name"
        const val KEY_RATING_LOW = "rating_low"
        const val KEY_RATING_HIGH = "rating_high"
        const val KEY_BAND = "band"
        const val KEY_AVERAGE_LOSS = "average_loss"
        const val KEY_ASSESSMENTS = "assessments"
        const val KEY_DECLINED = "assessment_declined"
    }
}
