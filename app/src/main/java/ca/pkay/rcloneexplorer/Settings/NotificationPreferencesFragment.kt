package ca.pkay.rcloneexplorer.Settings

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat

class NotificationPreferencesFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
    }
}
