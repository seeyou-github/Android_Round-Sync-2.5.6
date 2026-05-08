package ca.pkay.rcloneexplorer.Activities;

import android.content.Context;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import ca.pkay.rcloneexplorer.Fragments.CurrentSyncDetailsFragment;
import ca.pkay.rcloneexplorer.R;
import ca.pkay.rcloneexplorer.RuntimeConfiguration;
import ca.pkay.rcloneexplorer.util.ActivityHelper;
import ca.pkay.rcloneexplorer.util.CurrentSyncDetails;
import es.dmoral.toasty.Toasty;

public class CurrentSyncDetailsActivity extends AppCompatActivity {

    private static final int MENU_CLEAR_SYNC_LOG = 1;
    private static final String SYNC_LOG_FRAGMENT_TAG = "sync_log_fragment";

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(RuntimeConfiguration.attach(this, newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityHelper.applyTheme(this);
        setContentView(R.layout.activity_fragment_host);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle(R.string.current_sync_details);
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setDisplayShowHomeEnabled(true);
        }

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.flFragment, CurrentSyncDetailsFragment.newInstance(), SYNC_LOG_FRAGMENT_TAG)
                    .commit();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuItem clearItem = menu.add(Menu.NONE, MENU_CLEAR_SYNC_LOG, Menu.NONE, R.string.clear_logs);
        clearItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_CLEAR_SYNC_LOG) {
            CurrentSyncDetails.clear(this);
            CurrentSyncDetailsFragment fragment = (CurrentSyncDetailsFragment) getSupportFragmentManager().findFragmentByTag(SYNC_LOG_FRAGMENT_TAG);
            if (fragment != null) {
                fragment.refreshDetails();
            }
            Toasty.info(this, getString(R.string.logs_cleared), Toast.LENGTH_SHORT, true).show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }
}
