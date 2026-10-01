package hh.kensakudl.app.adapter;

import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import hh.kensakudl.app.MainActivity;
import hh.kensakudl.app.PlayerActivity;
import hh.kensakudl.app.R;
import hh.kensakudl.app.databinding.ItemQueueBinding;
import hh.kensakudl.app.databinding.ItemQueueGroupHeaderBinding;
import hh.kensakudl.app.downloader.DownloadManager;
import hh.kensakudl.app.model.DownloadItem;
import hh.kensakudl.app.model.DownloadStatus;
import hh.kensakudl.app.util.FileUtil;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
/**
 * Presents the download queue as collapsible anime groups and episode rows.
 *
 * <p>The adapter derives its display list from the manager's raw queue and keeps group
 * expansion state independently from download state.</p>
 */

public class QueueAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_EPISODE = 1;

    public interface ListItem {}

    public static class GroupHeaderItem implements ListItem {
        public final String animeTitle;
        public final int totalCount;
        public final int completedCount;
        public final int activeCount;
        public final int queuedCount;
        public final String summaryText;

        public GroupHeaderItem(
                String title,
                int total,
                int completed,
                int active,
                int queued,
                String summary
        ) {
            this.animeTitle = title;
            this.totalCount = total;
            this.completedCount = completed;
            this.activeCount = active;
            this.queuedCount = queued;
            this.summaryText = summary;
        }
    }

    public static class EpisodeRowItem implements ListItem {
        public final DownloadItem item;

        public EpisodeRowItem(DownloadItem item) {
            this.item = item;
        }
    }

    private final List<ListItem> displayList = new ArrayList<>();
    private final Map<String, Boolean> expandedStateMap = new HashMap<>();
    private final DownloadManager downloadManager;
    private List<DownloadItem> rawList = new ArrayList<>();

    public QueueAdapter(DownloadManager downloadManager) {
        this.downloadManager = downloadManager;
    }
/**
 * Replaces the queue data and rebuilds the grouped display representation.
 *
 * @param items current download queue
 */

    public void submitList(List<DownloadItem> items) {
        this.rawList = items != null
                ? new ArrayList<>(items)
                : new ArrayList<>();

        rebuildDisplayList();
    }

    private void rebuildDisplayList() {
        displayList.clear();

        Map<String, List<DownloadItem>> groups =
                new LinkedHashMap<>();

        for (DownloadItem item : rawList) {
            
            if (item == null
                    || item.getAnimeTitle() == null
                    || item.getAnimeTitle().trim().isEmpty()) {
                continue;
            }

            groups.computeIfAbsent(
                    item.getAnimeTitle(),
                    k -> new ArrayList<>()
            ).add(item);
        }

        for (Map.Entry<String, List<DownloadItem>> entry
                : groups.entrySet()) {

            String animeTitle = entry.getKey();
            List<DownloadItem> episodes = entry.getValue();

            
            if (episodes == null || episodes.isEmpty()) {
                continue;
            }

            int total = episodes.size();
            int completed = 0;
            int active = 0;
            int queued = 0;

            for (DownloadItem ep : episodes) {
                if (ep.getStatus() == DownloadStatus.COMPLETED
                        || ep.getStatus() == DownloadStatus.SKIPPED) {
                    completed++;
                } else if (ep.getStatus() == DownloadStatus.DOWNLOADING
                        || ep.getStatus() == DownloadStatus.RESOLVING) {
                    active++;
                } else if (ep.getStatus() == DownloadStatus.QUEUED) {
                    queued++;
                }
            }

            String summary;

            if (completed == total) {
                summary = total + " episodes • Completed";
            } else if (active > 0) {
                summary = total + " episodes • "
                        + active + " Downloading, "
                        + queued + " Queued";
            } else {
                summary = total + " episodes • "
                        + completed + " Done, "
                        + queued + " Queued";
            }

            displayList.add(
                    new GroupHeaderItem(
                            animeTitle,
                            total,
                            completed,
                            active,
                            queued,
                            summary
                    )
            );

            
            boolean isExpanded =
                    expandedStateMap.computeIfAbsent(
                            animeTitle,
                            k -> false
                    );

            if (isExpanded) {
                for (DownloadItem ep : episodes) {
                    displayList.add(
                            new EpisodeRowItem(ep)
                    );
                }
            }
        }

        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return displayList.get(position)
                instanceof GroupHeaderItem
                ? TYPE_HEADER
                : TYPE_EPISODE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(
            @NonNull ViewGroup parent,
            int viewType
    ) {
        LayoutInflater inflater =
                LayoutInflater.from(parent.getContext());

        if (viewType == TYPE_HEADER) {
            ItemQueueGroupHeaderBinding binding =
                    ItemQueueGroupHeaderBinding.inflate(
                            inflater,
                            parent,
                            false
                    );

            return new HeaderViewHolder(binding);
        } else {
            ItemQueueBinding binding =
                    ItemQueueBinding.inflate(
                            inflater,
                            parent,
                            false
                    );

            return new EpisodeViewHolder(binding);
        }
    }

    @Override
    public void onBindViewHolder(
            @NonNull RecyclerView.ViewHolder holder,
            int position
    ) {
        ListItem listItem = displayList.get(position);

        if (holder instanceof HeaderViewHolder) {
            HeaderViewHolder h =
                    (HeaderViewHolder) holder;

            GroupHeaderItem header =
                    (GroupHeaderItem) listItem;

            h.binding.tvGroupTitle.setText(
                    header.animeTitle
            );

            h.binding.tvGroupSubtitle.setText(
                    header.summaryText
            );

            boolean isExpanded =
                    expandedStateMap.getOrDefault(
                            header.animeTitle,
                            false
                    );

            h.binding.ivExpandChevron.setImageResource(
                    isExpanded
                            ? R.drawable.ic_chevron_up
                            : R.drawable.ic_chevron_down
            );

            h.binding.llGroupHeader.setOnClickListener(v -> {
                expandedStateMap.put(
                        header.animeTitle,
                        !isExpanded
                );

                rebuildDisplayList();
            });

            h.binding.btnDeleteGroup.setOnClickListener(v -> {
                new MaterialAlertDialogBuilder(
                        v.getContext(),
                        R.style.KensakuDialogTheme
                )
                        .setTitle("Remove Series?")
                        .setMessage(
                                "Are you sure you want to cancel and remove all episodes of \""
                                        + header.animeTitle
                                        + "\" from your downloads?"
                        )
                        .setPositiveButton(
                                "Remove",
                                (dialog, which) ->
                                        downloadManager.removeAnimeSeries(
                                                header.animeTitle
                                        )
                        )
                        .setNegativeButton(
                                "Cancel",
                                null
                        )
                        .show();
            });

        } else if (holder instanceof EpisodeViewHolder) {
            EpisodeViewHolder h =
                    (EpisodeViewHolder) holder;

            DownloadItem item =
                    ((EpisodeRowItem) listItem).item;

            if (item.getStatus() == DownloadStatus.COMPLETED
                    || item.getStatus() == DownloadStatus.SKIPPED) {

                h.binding.layoutActiveCard.setVisibility(
                        View.GONE
                );

                h.binding.layoutCompletedRow.setVisibility(
                        View.VISIBLE
                );

                h.binding.tvCompletedEpisodeName.setText(
                        item.getAnimeTitle()
                                + " — Ep "
                                + item.getEpisodeName()
                );

                h.binding.tvCompletedSizeBadge.setText(
                        item.getSize()
                );

                
                h.binding.layoutCompletedRow.setOnClickListener(v -> {
                    File videoFile = null;

                    if (item.getOutputPath() != null
                            && !item.getOutputPath().isEmpty()) {

                        File candidate =
                                new File(item.getOutputPath());

                        if (candidate.exists()) {
                            videoFile = candidate;
                        }
                    }

                    if (videoFile == null) {
                        videoFile =
                                FileUtil.getFinalMp4File(
                                        item.getAnimeTitle(),
                                        item.getEpisodeName()
                                );
                    }

                    if (videoFile.exists()) {
                        File finalVideoFile = videoFile;

                        Runnable openPlayer = () -> {
                            Intent intent =
                                    new Intent(
                                            v.getContext(),
                                            PlayerActivity.class
                                    );

                            intent.putExtra(
                                    PlayerActivity.EXTRA_VIDEO_PATH,
                                    finalVideoFile.getAbsolutePath()
                            );

                            intent.putExtra(
                                    PlayerActivity.EXTRA_ANIME_TITLE,
                                    item.getAnimeTitle()
                            );

                            intent.putExtra(
                                    PlayerActivity.EXTRA_EPISODE_NAME,
                                    item.getEpisodeName()
                            );

                            v.getContext().startActivity(intent);
                        };

                        if (v.getContext() instanceof MainActivity) {
                            ((MainActivity) v.getContext())
                                    .requestVideoAccess(openPlayer);
                        } else {
                            openPlayer.run();
                        }

                    } else {
                        Toast.makeText(
                                v.getContext(),
                                "Video file not found in storage",
                                Toast.LENGTH_SHORT
                        ).show();
                    }
                });

                h.binding.btnTrashCompleted.setOnClickListener(v -> {
                    new MaterialAlertDialogBuilder(
                            v.getContext(),
                            R.style.KensakuDialogTheme
                    )
                            .setTitle("Delete Episode?")
                            .setMessage(
                                    "Delete Episode "
                                            + item.getEpisodeName()
                                            + " of \""
                                            + item.getAnimeTitle()
                                            + "\" from storage?"
                            )
                            .setPositiveButton(
                                    "Delete",
                                    (dialog, which) ->
                                            downloadManager.remove(
                                                    item.getId()
                                            )
                            )
                            .setNegativeButton(
                                    "Cancel",
                                    null
                            )
                            .show();
                });

            } else {
                h.binding.layoutCompletedRow.setVisibility(
                        View.GONE
                );

                h.binding.layoutActiveCard.setVisibility(
                        View.VISIBLE
                );

                h.binding.tvQueueAnimeTitle.setText(
                        item.getAnimeTitle()
                );

                h.binding.tvQueueEpisodeName.setText(
                        "Episode " + item.getEpisodeName()
                );

                h.binding.tvQueueStatus.setText(
                        item.getStatus().name()
                );

                h.binding.pbQueueDownload.setProgress(
                        item.getProgress()
                );

                String info =
                        "Progress: "
                                + item.getProgress()
                                + "% • "
                                + item.getSize()
                                + "  "
                                + item.getSpeed()
                                + " • ETA: "
                                + item.getEta();

                h.binding.tvQueueProgressInfo.setText(info);

                if (item.getError() != null
                        && !item.getError().isEmpty()) {

                    h.binding.tvQueueError.setVisibility(
                            View.VISIBLE
                    );

                    h.binding.tvQueueError.setText(
                            item.getError()
                    );

                } else {
                    h.binding.tvQueueError.setVisibility(
                            View.GONE
                    );
                }

                bindActions(h, item);
            }
        }
    }

    private void bindActions(
            EpisodeViewHolder holder,
            DownloadItem item
    ) {
        DownloadStatus s = item.getStatus();

        if (s == DownloadStatus.QUEUED) {
            holder.binding.btnActionPrimary.setVisibility(
                    View.VISIBLE
            );

            holder.binding.btnActionPrimary.setText(
                    "Start"
            );

            holder.binding.btnActionPrimary.setOnClickListener(
                    v -> downloadManager.start(item.getId())
            );

            holder.binding.btnActionSecondary.setVisibility(
                    View.VISIBLE
            );

            holder.binding.btnActionSecondary.setText(
                    "Cancel"
            );

            holder.binding.btnActionSecondary.setOnClickListener(
                    v -> downloadManager.cancel(item.getId())
            );

        } else if (s == DownloadStatus.DOWNLOADING
                || s == DownloadStatus.RESOLVING) {

            holder.binding.btnActionPrimary.setVisibility(
                    View.VISIBLE
            );

            holder.binding.btnActionPrimary.setText(
                    "Pause"
            );

            holder.binding.btnActionPrimary.setOnClickListener(
                    v -> downloadManager.pause(item.getId())
            );

            holder.binding.btnActionSecondary.setVisibility(
                    View.VISIBLE
            );

            holder.binding.btnActionSecondary.setText(
                    "Cancel"
            );

            holder.binding.btnActionSecondary.setOnClickListener(
                    v -> downloadManager.cancel(item.getId())
            );

        } else if (s == DownloadStatus.PAUSED) {
            holder.binding.btnActionPrimary.setVisibility(
                    View.VISIBLE
            );

            holder.binding.btnActionPrimary.setText(
                    "Resume"
            );

            holder.binding.btnActionPrimary.setOnClickListener(
                    v -> downloadManager.resume(item.getId())
            );

            holder.binding.btnActionSecondary.setVisibility(
                    View.VISIBLE
            );

            holder.binding.btnActionSecondary.setText(
                    "Cancel"
            );

            holder.binding.btnActionSecondary.setOnClickListener(
                    v -> downloadManager.cancel(item.getId())
            );

        } else if (s == DownloadStatus.FAILED) {
            holder.binding.btnActionPrimary.setVisibility(
                    View.VISIBLE
            );

            holder.binding.btnActionPrimary.setText(
                    "Retry"
            );

            holder.binding.btnActionPrimary.setOnClickListener(
                    v -> downloadManager.retry(item.getId())
            );

            holder.binding.btnActionSecondary.setVisibility(
                    View.VISIBLE
            );

            holder.binding.btnActionSecondary.setText(
                    "Remove"
            );

            holder.binding.btnActionSecondary.setOnClickListener(
                    v -> downloadManager.remove(item.getId())
            );

        } else if (s == DownloadStatus.CANCELLED) {
            holder.binding.btnActionPrimary.setVisibility(
                    View.GONE
            );

            holder.binding.btnActionSecondary.setVisibility(
                    View.VISIBLE
            );

            holder.binding.btnActionSecondary.setText(
                    "Remove"
            );

            holder.binding.btnActionSecondary.setOnClickListener(
                    v -> downloadManager.remove(item.getId())
            );
        }
    }

    @Override
    public int getItemCount() {
        return displayList.size();
    }

    static class HeaderViewHolder
            extends RecyclerView.ViewHolder {

        final ItemQueueGroupHeaderBinding binding;

        HeaderViewHolder(
                ItemQueueGroupHeaderBinding binding
        ) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    static class EpisodeViewHolder
            extends RecyclerView.ViewHolder {

        final ItemQueueBinding binding;

        EpisodeViewHolder(
                ItemQueueBinding binding
        ) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}