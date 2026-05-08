package ca.pkay.rcloneexplorer.Fragments;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import ca.pkay.rcloneexplorer.R;
import ca.pkay.rcloneexplorer.util.CurrentSyncDetails;

import java.util.ArrayList;
import java.util.List;

public class CurrentSyncDetailsFragment extends Fragment {

    private SyncDetailsAdapter adapter;
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
        RecyclerView detailsList = view.findViewById(R.id.current_sync_details_list);
        detailsList.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new SyncDetailsAdapter(requireContext());
        detailsList.setAdapter(adapter);
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

    public void refreshDetails() {
        updateDetails();
    }

    private void updateDetails() {
        if (adapter == null || getContext() == null) {
            return;
        }
        ArrayList<String> details = CurrentSyncDetails.readLines(requireContext());
        if (details.isEmpty()) {
            details.add(getString(R.string.current_sync_details_empty));
        }
        adapter.submit(details);
    }

    private static class SyncDetailsAdapter extends RecyclerView.Adapter<SyncDetailsAdapter.ViewHolder> {

        private final Context context;
        private final int textColor;
        private final ArrayList<String> lines = new ArrayList<>();

        SyncDetailsAdapter(Context context) {
            this.context = context;
            this.textColor = resolveTextColor(context);
        }

        void submit(List<String> newLines) {
            if (lines.equals(newLines)) {
                return;
            }
            lines.clear();
            lines.addAll(newLines);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView textView = new TextView(context);
            int verticalPadding = dp(4);
            int horizontalPadding = dp(2);
            textView.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            textView.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);
            textView.setTypeface(Typeface.MONOSPACE);
            textView.setTextColor(textColor);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            return new ViewHolder(textView);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            holder.textView.setText(lines.get(position));
        }

        @Override
        public int getItemCount() {
            return lines.size();
        }

        private int dp(int value) {
            return (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    value,
                    context.getResources().getDisplayMetrics()
            );
        }

        private static int resolveTextColor(Context context) {
            TypedValue value = new TypedValue();
            context.getTheme().resolveAttribute(android.R.attr.textColorPrimary, value, true);
            if (value.resourceId != 0) {
                return ContextCompat.getColor(context, value.resourceId);
            }
            return value.data;
        }

        private static class ViewHolder extends RecyclerView.ViewHolder {
            private final TextView textView;

            ViewHolder(@NonNull TextView textView) {
                super(textView);
                this.textView = textView;
            }
        }
    }
}
