package ca.pkay.rcloneexplorer.Fragments;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import ca.pkay.rcloneexplorer.R;
import ca.pkay.rcloneexplorer.util.CurrentSyncDetails;

public class CurrentSyncDetailsFragment extends Fragment {

    private TextView detailsText;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            updateDetails();
            handler.postDelayed(this, 1000);
        }
    };

    public static CurrentSyncDetailsFragment newInstance() {
        return new CurrentSyncDetailsFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_current_sync_details, container, false);
        detailsText = view.findViewById(R.id.current_sync_details_text);
        updateDetails();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshRunnable.run();
    }

    @Override
    public void onPause() {
        super.onPause();
        handler.removeCallbacks(refreshRunnable);
    }

    private void updateDetails() {
        if (detailsText == null || getContext() == null) {
            return;
        }
        String details = CurrentSyncDetails.read(requireContext());
        if (details.isEmpty()) {
            detailsText.setText(R.string.current_sync_details_empty);
        } else {
            detailsText.setText(details);
        }
    }
}
