package ca.pkay.rcloneexplorer.Settings

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreference
import ca.pkay.rcloneexplorer.Log2File
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.util.FLog
import de.felixnuesse.extract.extensions.tag
import de.felixnuesse.extract.settings.preferences.ButtonPreference
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.regex.Pattern


class LogPreferencesFragment : PreferenceFragmentCompat() {

    private val requestLogLocation = 901
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var logLocationPreference: Preference
    private lateinit var useLogsPreference: SwitchPreference

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_logging_preferences, rootKey)
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        requireActivity().title = getString(R.string.logging_settings_header)

        logLocationPreference = findPreference(getString(R.string.pref_key_log_location_uri))!!
        useLogsPreference = findPreference(getString(R.string.pref_key_logs))!!

        logLocationPreference.setOnPreferenceClickListener {
            openLogLocationPicker()
            true
        }
        useLogsPreference.setOnPreferenceChangeListener { _, newValue ->
            if (newValue == true && hasConfiguredInvalidLogLocation()) {
                Toast.makeText(context, R.string.log_location_no_permission, Toast.LENGTH_LONG).show()
                false
            } else {
                true
            }
        }
        updateLogLocationState()

        val sigkill = findPreference<Preference>("TempKeySigquit") as ButtonPreference
        sigkill.setButtonText(getString(R.string.pref_send_sigquit_button))
        sigkill.setButtonOnClick {
            sigquitAll()
        }

    }

    private fun openLogLocationPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        startActivityForResult(intent, requestLogLocation)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != requestLogLocation) {
            return
        }
        if (resultCode != Activity.RESULT_OK || data?.data == null) {
            Toast.makeText(context, R.string.log_location_cancelled, Toast.LENGTH_SHORT).show()
            return
        }

        val uri = data.data ?: return
        val takeFlags = data.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            requireContext().contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (e: SecurityException) {
            FLog.e(tag(), "Could not persist log location permission", e)
        }

        if (Log2File.testLogLocation(requireContext(), uri)) {
            sharedPreferences.edit()
                .putString(getString(R.string.pref_key_log_location_uri), uri.toString())
                .apply()
            Toast.makeText(context, R.string.log_location_saved, Toast.LENGTH_SHORT).show()
        } else {
            sharedPreferences.edit()
                .remove(getString(R.string.pref_key_log_location_uri))
                .putBoolean(getString(R.string.pref_key_logs), false)
                .apply()
            Toast.makeText(context, R.string.log_location_no_permission, Toast.LENGTH_LONG).show()
        }
        updateLogLocationState()
    }

    private fun updateLogLocationState() {
        val uriString = sharedPreferences.getString(getString(R.string.pref_key_log_location_uri), "") ?: ""
        val hasLocation = uriString.isNotEmpty()
        if (hasLocation && hasPersistedWritePermission(Uri.parse(uriString))) {
            logLocationPreference.summary = Uri.parse(uriString).lastPathSegment ?: uriString
        } else if (hasLocation) {
            logLocationPreference.setSummary(R.string.log_location_no_permission)
        } else {
            logLocationPreference.setSummary(R.string.log_location_not_set)
        }
        val invalidConfiguredLocation = hasConfiguredInvalidLogLocation()
        useLogsPreference.isEnabled = !invalidConfiguredLocation
        if (invalidConfiguredLocation) {
            useLogsPreference.isChecked = false
            sharedPreferences.edit().putBoolean(getString(R.string.pref_key_logs), false).apply()
        }
    }

    private fun hasConfiguredInvalidLogLocation(): Boolean {
        val uriString = sharedPreferences.getString(getString(R.string.pref_key_log_location_uri), "") ?: ""
        return uriString.isNotEmpty() && !hasPersistedWritePermission(Uri.parse(uriString))
    }

    private fun hasPersistedWritePermission(uri: Uri): Boolean {
        return requireContext().contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
    }


    private fun sigquitAll() {
        Toast.makeText(context, "Round Sync: Stopping everything", Toast.LENGTH_LONG).show()
        try {
            val runtime = Runtime.getRuntime()
            val process = runtime.exec("ps")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            val output = StringBuilder()
            while ((reader.readLine().also { line = it }) != null) {
                output.append('\n')
                output.append(line)
            }

            process.waitFor()

            val regex = "\\s+(\\d+)\\s+\\d+\\s+\\d+\\s+.+librclone.+$"
            val pattern = Pattern.compile(regex, Pattern.MULTILINE)
            val matcher = pattern.matcher(output.toString())

            while (matcher.find()) {
                for (i in 1..matcher.groupCount()) {
                    val pidMatch = matcher.group(i) ?: continue
                    val pid = pidMatch.toInt()
                    FLog.i(tag(), "SIGQUIT to process pid=%s", pid)
                    Process.sendSignal(pid, Process.SIGNAL_QUIT)
                }
            }
            Process.killProcess(Process.myPid())
        } catch (e: IOException) {
            FLog.e(tag(), "Error executing shell commands", e)
        } catch (e: InterruptedException) {
            FLog.e(tag(), "Error executing shell commands", e)
        }
    }
}
