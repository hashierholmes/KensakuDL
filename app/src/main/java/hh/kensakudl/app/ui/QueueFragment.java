package hh.kensakudl.app.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import hh.kensakudl.app.adapter.QueueAdapter;
import hh.kensakudl.app.databinding.FragmentQueueBinding;
import hh.kensakudl.app.downloader.DownloadManager;
import hh.kensakudl.app.model.DownloadItem;
import hh.kensakudl.app.model.DownloadStatus;
import java.util.ArrayList;
import java.util.List;

/**
 * Displays the download queue and observes DownloadManager so visible progress and queue structure stay in sync with background work.
 */
public class QueueFragment extends Fragment implements DownloadManager.DownloadListener {
    private FragmentQueueBinding binding;
    private QueueAdapter adapter;
    private DownloadManager downloadManager;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentQueueBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    /**
     * Initializes the queue list and connects row actions to DownloadManager.
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        downloadManager = DownloadManager.getInstance(requireContext());
        downloadManager.addListener(this);

        binding.rvQueue.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new QueueAdapter(downloadManager);
        binding.rvQueue.setAdapter(adapter);

        binding.btnDownloadSpeed.setText("CLEAR FINISHED");
        binding.btnDownloadSpeed.setOnClickListener(v -> downloadManager.clearFinished());

        downloadManager.syncWithDiskStorage();
        refreshQueue();
    }

    /**
     * Refreshes the visible queue when the fragment returns to the foreground.
     */
    @Override
    public void onResume() {
        super.onResume();
        if (downloadManager != null) {
            downloadManager.syncWithDiskStorage();
            refreshQueue();
        }
    }

    private void refreshQueue() {
        List<DownloadItem> allItems = downloadManager.getQueue();
        List<DownloadItem> validItems = new ArrayList<>();

        int total = 0;
        int done = 0;
        int failed = 0;
        int skipped = 0;

        for (DownloadItem it : allItems) {
            // Strictly exclude blank or invalid items
            if (it == null || it.getAnimeTitle() == null || it.getAnimeTitle().trim().isEmpty()) {
                continue;
            }
            validItems.add(it);
            total++;

            if (it.getStatus() == DownloadStatus.COMPLETED) done++;
            else if (it.getStatus() == DownloadStatus.FAILED) failed++;
            else if (it.getStatus() == DownloadStatus.SKIPPED) skipped++;
        }

        adapter.submitList(validItems);
        binding.layoutQueueEmpty.setVisibility(validItems.isEmpty() ? View.VISIBLE : View.GONE);
        binding.rvQueue.setVisibility(validItems.isEmpty() ? View.GONE : View.VISIBLE);
        binding.btnDownloadSpeed.setText("CLEAR FINISHED");
        binding.btnDownloadSpeed.setVisibility(done + failed + skipped > 0 ? View.VISIBLE : View.GONE);

        binding.tvTotal.setText("Total: " + total);
        binding.tvDone.setText("Done: " + done);
        binding.tvFailed.setText("Failed: " + failed);
        binding.tvSkipped.setText("Skipped: " + skipped);
    }

    /**
     * Refreshes the affected queue row after its download state changes.
     * 
     * @param item updated download item
     */
    @Override
    public void onDownloadUpdated(DownloadItem item) {
        refreshQueue();
    }

    /**
     * Rebuilds the visible queue grouping after items are added or removed.
     */
    @Override
    public void onQueueChanged() {
        refreshQueue();
    }

    /**
     * Unregisters view-bound listeners and clears binding references to avoid retaining the destroyed view.
     */
    @Override
    public void onDestroyView() {
        downloadManager.removeListener(this);
        super.onDestroyView();
        binding = null;
    }
}