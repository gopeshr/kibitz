package gopesh.kibitz.profile

/**
 * What Kibitz remembers about the player between launches.
 *
 * The only personal data here is a display name the player types in themselves. It stays on
 * the device and is never sent anywhere. When the coaching layer starts calling a model, the
 * name must not be part of the request — nothing about the coaching needs it.
 */
data class UserProfile(
    val name: String,
    val ratingLow: Int = 0,
    val ratingHigh: Int = 0,
    val bandLabel: String = "",
    val averageLoss: Int = 0,
    val assessmentsCompleted: Int = 0,
    /** True once the player has chosen to skip the level check, so it is never forced again. */
    val assessmentDeclined: Boolean = false,
) {
    val hasLevel: Boolean get() = bandLabel.isNotEmpty()

    val ratingText: String get() = if (hasLevel) "$ratingLow–$ratingHigh" else "Unrated"

    /** First name only, for greetings, so a long full name does not break the layout. */
    val shortName: String get() = name.trim().split(' ').first().take(18)
}
