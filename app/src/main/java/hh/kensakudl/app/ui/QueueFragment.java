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
 * Displays and controls the active download queue.
 *
 * <p>The fragment observes {@link DownloadManager} rather than performing download work
 * itself, allowing the queue to survive normal fragment recreation.</p>
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

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        downloadManager = DownloadManager.getInstance(requireContext());
        downloadManager.addListener(this);

        binding.rvQueue.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new QueueAdapter(downloadManager);
        binding.rvQueue.setAdapter(adapter);

        binding.btnClearFinished.setOnClickListener(v -> downloadManager.clearFinished());

        downloadManager.syncWithDiskStorage();
        refreshQueue();
    }

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

        binding.tvTotal.setText("Total: " + total);
        binding.tvDone.setText("Done: " + done);
        binding.tvFailed.setText("Failed: " + failed);
        binding.tvSkipped.setText("Skipped: " + skipped);
    }

    @Override
    public void onDownloadUpdated(DownloadItem item) {
        refreshQueue();
    }

    @Override
    public void onQueueChanged() {
        refreshQueue();
    }

    @Override
    public void onDestroyView() {
        downloadManager.removeListener(this);
        super.onDestroyView();
        binding = null;
    }
}