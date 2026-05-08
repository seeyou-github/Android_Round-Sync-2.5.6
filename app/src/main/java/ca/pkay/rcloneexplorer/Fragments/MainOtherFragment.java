package ca.pkay.rcloneexplorer.Fragments;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import ca.pkay.rcloneexplorer.Activities.AboutActivity;
import ca.pkay.rcloneexplorer.Activities.CurrentSyncDetailsActivity;
import ca.pkay.rcloneexplorer.Activities.SettingsActivity;
import ca.pkay.rcloneexplorer.R;

public class MainOtherFragment extends Fragment {

    private NavigationListener navigationListener;

    public interface NavigationListener {
        void onOtherTriggersSelected();
        void onOtherLogsSelected();
    }

    public static MainOtherFragment newInstance() {
        return new MainOtherFragment();
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof NavigationListener) {
            navigationListener = (NavigationListener) context;
        } else {
            throw new RuntimeException(context + " must implement MainOtherFragment.NavigationListener");
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        navigationListener = null;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_main_other, container, false);

        view.findViewById(R.id.other_triggers).setOnClickListener(v -> navigationListener.onOtherTriggersSelected());
        view.findViewById(R.id.other_logs).setOnClickListener(v -> navigationListener.onOtherLogsSelected());
        view.findViewById(R.id.other_current_sync_details).setOnClickListener(v -> startActivity(new Intent(requireContext(), CurrentSyncDetailsActivity.class)));
        view.findViewById(R.id.other_settings).setOnClickListener(v -> startActivity(new Intent(requireContext(), SettingsActivity.class)));
        view.findViewById(R.id.other_about).setOnClickListener(v -> startActivity(new Intent(requireContext(), AboutActivity.class)));

        return view;
    }
}
