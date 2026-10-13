package hh.kensakudl.app.ui;

import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.GridLayoutManager;
import android.content.Intent;
import android.text.TextUtils;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import hh.kensakudl.app.MainActivity;
import hh.kensakudl.app.R;
import hh.kensakudl.app.adapter.EpisodeAdapter;
import hh.kensakudl.app.databinding.FragmentSeriesBinding;
import hh.kensakudl.app.downloader.DownloadManager;
import hh.kensakudl.app.model.AnimeItem;
import hh.kensakudl.app.model.EpisodeItem;
import hh.kensakudl.app.network.ApiClient;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Displays an anime's details and episode list, and coordinates track selection, playback, bookmarking, and batch download actions.
 */
public class SeriesFragment extends Fragment {
    private static final String ARG_ANIME = "arg_anime";
    private static final String TRACK_SUB = "sub";
    private static final String TRACK_DUB = "dub";

    private FragmentSeriesBinding binding;
    private AnimeItem anime;
    private EpisodeAdapter adapter;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService episodeExecutor = Executors.newSingleThreadExecutor();

    private List<EpisodeItem> subEpisodes = Collections.emptyList();
    private List<EpisodeItem> dubEpisodes = Collections.emptyList();
    private boolean subLoaded;
    private boolean dubLoaded;
    private String selectedTrack = TRACK_SUB;
    private boolean downloadMode = true;

    /**
     * Creates a detail fragment carrying the selected anime as its arguments.
     * 
     * @param anime anime whose details should be shown
     * @return configured series fragment
     */
    public static SeriesFragment newInstance(AnimeItem anime) {
        SeriesFragment fragment = new SeriesFragment();
        Bundle bundle = new Bundle();
        bundle.putSerializable(ARG_ANIME, anime);
        fragment.setArguments(bundle);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentSeriesBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    /**
     * Binds anime details and episodes, then configures track, playback, bookmark, and download interactions.
     */
    @Override
    @SuppressWarnings("deprecation")
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (getArguments() != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                anime = getArguments().getSerializable(ARG_ANIME, AnimeItem.class);
            } else {
                anime = (AnimeItem) getArguments().getSerializable(ARG_ANIME);
            }
        }
        if (anime == null) return;

        binding.btnBack.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).navigateToSearch();
            }
        });

        binding.tvSeriesTitle.setText(anime.getPostName());
        binding.tvHeroTitle.setText(anime.getPostName());
        binding.tvYear.setText(anime.getPostYear());
        binding.tvType.setText(anime.getAniTypes());
        binding.tvSeason.setText(anime.getPostSeasonType());
        binding.tvGenres.setText(anime.getPostAniGenres());
        binding.tvDescription.setText(TextUtils.isEmpty(anime.getDescription()) ? "No synopsis is available for this title yet." : anime.getDescription().trim());
        binding.tvStatus.setText(TextUtils.isEmpty(anime.getStatus()) ? "ANIME" : anime.getStatus().replace('_', ' '));
        String meta = anime.getPostYear() + "  •  " + anime.getAniTypes() +
                (anime.getEpisodeCount() > 0 ? "  •  " + anime.getEpisodeCount() + " eps" : "") +
                (anime.getAverageScore() > 0 ? "  •  ★ " + formatScore(anime.getAverageScore()) : "");
        binding.tvMetaSummary.setText(meta);

        Glide.with(this).load(anime.getBannerUrl().isEmpty() ? anime.getBackgroundUrl() : anime.getBannerUrl())
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC).placeholder(R.drawable.bg_pill).error(R.drawable.bg_pill).into(binding.ivSeriesBanner);
        Glide.with(this).load(anime.getAniCoverLarge()).diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .placeholder(R.drawable.bg_pill).error(R.drawable.bg_pill).into(binding.ivSeriesCover);

        binding.rvEpisodes.setLayoutManager(new GridLayoutManager(requireContext(), 4));
        adapter = new EpisodeAdapter(count -> {
            if (binding == null) return;
            binding.tvSelectedCount.setText(count + " episodes selected");
            binding.btnDownloadSelected.setEnabled(count > 0);
        }, episode -> { /* Download-only mode: episode tiles select for download. */ });
        adapter.setDownloadMode(true);
        binding.rvEpisodes.setAdapter(adapter);
        binding.btnWatchMode.setVisibility(View.GONE);
        binding.btnDownloadMode.setText("DOWNLOAD EPISODES");
        binding.btnDownloadMode.setVisibility(View.GONE);
        setMode(true);

        ArrayAdapter<String> trackAdapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_item,
                new String[]{"Sub", "Dub"}
        );
        trackAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.spinnerTrack.setAdapter(trackAdapter);
        binding.spinnerTrack.setSelection(TRACK_DUB.equals(selectedTrack) ? 1 : 0, false);
        binding.spinnerTrack.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            /**
             * Updates the displayed episode list when the user changes the available audio track.
             * 
             * @param parent spinner that owns the selection
             * @param view selected row view, if available
             * @param position selected track position
             * @param id selected row identifier
             */
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String track = position == 1 ? TRACK_DUB : TRACK_SUB;
                if (!track.equals(selectedTrack) || (!subLoaded && TRACK_SUB.equals(track))) {
                    selectedTrack = track;
                    showTrackEpisodes(track);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        binding.btnSelectAll.setOnClickListener(v -> adapter.selectAll(true));
        binding.btnClear.setOnClickListener(v -> adapter.selectAll(false));

        binding.btnDownloadSelected.setOnClickListener(v -> {
            List<EpisodeItem> selected = adapter.getSelectedEpisodes();
            if (selected.isEmpty()) return;

            Runnable enqueueAction = () -> {
                DownloadManager dm = DownloadManager.getInstance(requireContext());
                dm.enqueueBatch(anime.getPostName(), selected);
                Toast.makeText(
                        requireContext(),
                        "Added " + selected.size() + " episodes to queue",
                        Toast.LENGTH_SHORT
                ).show();

                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).navigateToQueue();
                }
            };

            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).requestNotificationPermission(enqueueAction);
            } else {
                enqueueAction.run();
            }
        });

        showTrackEpisodes(TRACK_SUB);
    }

    private void showTrackEpisodes(String track) {
        if (binding == null) return;

        List<EpisodeItem> cached = TRACK_DUB.equals(track) ? dubEpisodes : subEpisodes;
        boolean loaded = TRACK_DUB.equals(track) ? dubLoaded : subLoaded;

        if (loaded) {
            applyEpisodes(cached, track);
            return;
        }

        binding.pbEpisodes.setVisibility(View.VISIBLE);
        binding.tvNoEpisodes.setVisibility(View.GONE);
        binding.rvEpisodes.setVisibility(View.GONE);

        episodeExecutor.execute(() -> {
            try {
                List<EpisodeItem> episodes = ApiClient.getInstance().getEpisodes(anime.getId(), track);
                mainHandler.post(() -> {
                    if (binding == null) return;
                    if (TRACK_DUB.equals(track)) {
                        dubEpisodes = episodes;
                        dubLoaded = true;
                    } else {
                        subEpisodes = episodes;
                        subLoaded = true;
                    }
                    if (track.equals(selectedTrack)) {
                        applyEpisodes(episodes, track);
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (binding == null) return;
                    binding.pbEpisodes.setVisibility(View.GONE);
                    binding.rvEpisodes.setVisibility(View.GONE);
                    binding.tvNoEpisodes.setVisibility(View.VISIBLE);
                    binding.tvNoEpisodes.setText(
                            TRACK_DUB.equals(track)
                                    ? "Couldn't load dub episodes: " + readableError(e)
                                    : "Couldn't load episodes: " + readableError(e)
                    );
                    Log.e("SeriesFragment", "Episode request failed", e);
                });
            }
        });
    }


    private void setMode(boolean download) {
        downloadMode = download;
        if (adapter != null) adapter.setDownloadMode(downloadMode);
        if (binding != null) {
            binding.rvEpisodes.setLayoutManager(new GridLayoutManager(requireContext(), 4));
            binding.layoutDownloadTools.setVisibility(downloadMode ? View.VISIBLE : View.GONE);
            binding.layoutDownloadBar.setVisibility(downloadMode ? View.VISIBLE : View.GONE);
            binding.tvSelectedCount.setText(adapter == null ? "0 episodes selected" : adapter.getSelectedCount() + " episodes selected");
            updateModeButtons();
        }
    }

    private void updateModeButtons() {
        if (binding == null) return;
        binding.btnWatchMode.setBackgroundTintList(android.content.res.ColorStateList.valueOf(downloadMode ? android.graphics.Color.rgb(37,42,59) : android.graphics.Color.rgb(139,43,63)));
        binding.btnDownloadMode.setBackgroundTintList(android.content.res.ColorStateList.valueOf(downloadMode ? android.graphics.Color.rgb(139,43,63) : android.graphics.Color.rgb(37,42,59)));
    }

    private String formatScore(double raw) {
        double score = raw > 10 ? raw / 10.0 : (raw <= 1.0 ? raw * 10.0 : raw);
        return String.format(java.util.Locale.US, "%.1f", score);
    }

    private void playEpisode(EpisodeItem episode) {
        if (episode == null || binding == null) return;
        binding.pbEpisodes.setVisibility(View.VISIBLE);
        binding.tvNoEpisodes.setVisibility(View.GONE);
        episodeExecutor.execute(() -> {
            try {
                ApiClient.StreamSource source = ApiClient.getInstance().resolveStreamingSource(episode.getLink(), selectedTrack);
                mainHandler.post(() -> {
                    if (binding == null || !isAdded()) return;
                    binding.pbEpisodes.setVisibility(View.GONE);
                    Intent intent = new Intent(requireContext(), hh.kensakudl.app.PlayerActivity.class);
                    intent.putExtra(hh.kensakudl.app.PlayerActivity.EXTRA_STREAM_URL, source.url);
                    intent.putExtra(hh.kensakudl.app.PlayerActivity.EXTRA_STREAM_REFERER, source.referer);
                    intent.putExtra(hh.kensakudl.app.PlayerActivity.EXTRA_STREAM_ORIGIN, source.origin);
                    intent.putExtra(hh.kensakudl.app.PlayerActivity.EXTRA_STREAM_SUBTITLE, source.subtitleUrl);
                    intent.putExtra(hh.kensakudl.app.PlayerActivity.EXTRA_ANIME_TITLE, anime.getPostName());
                    String epTitle = episode.getTitle();
                    int number = episode.getEpisodeNumber();
                    intent.putExtra(hh.kensakudl.app.PlayerActivity.EXTRA_EPISODE_NAME, "Episode " + number + (epTitle.isEmpty() ? "" : " • " + epTitle));
                    startActivity(intent);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (binding == null) return;
                    binding.pbEpisodes.setVisibility(View.GONE);
                    Toast.makeText(requireContext(), "Couldn't start stream: " + readableError(e), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private String readableError(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String message = cause.getMessage();
        if (message == null || message.trim().isEmpty()) message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return "Unknown error. Check your connection and retry.";
        if (message.contains("HTTP 400") || message.contains("request failed: 400")) return "The anime server rejected the request (HTTP 400).";
        if (message.contains("HTTP 403") || message.contains("request failed: 403")) return "The anime server blocked the request (HTTP 403). Try again later.";
        if (message.contains("HTTP 404") || message.contains("request failed: 404")) return "This anime or its episode list was not found (HTTP 404).";
        if (message.contains("HTTP 5") || message.contains("request failed: 5")) return "The anime server is having a problem. Try again later.";
        if (message.toLowerCase(java.util.Locale.ROOT).contains("unable to resolve host") || message.toLowerCase(java.util.Locale.ROOT).contains("failed to connect")) return "Couldn't connect to the anime server. Check your internet connection.";
        return message;
    }

    private void applyEpisodes(List<EpisodeItem> episodes, String track) {
        if (binding == null) return;
        binding.pbEpisodes.setVisibility(View.GONE);
        binding.tvSelectedCount.setText("0 episodes selected");
        binding.btnDownloadSelected.setEnabled(false);

        if (episodes == null || episodes.isEmpty()) {
            binding.rvEpisodes.setVisibility(View.GONE);
            binding.tvNoEpisodes.setVisibility(View.VISIBLE);
            binding.tvNoEpisodes.setText(
                    TRACK_DUB.equals(track)
                            ? "No Available Dub Episodes for this Series."
                            : "No episodes found for this series."
            );
            return;
        }

        binding.tvNoEpisodes.setVisibility(View.GONE);
        binding.rvEpisodes.setVisibility(View.VISIBLE);
        binding.tvEpisodeCount.setText(episodes.size() + (episodes.size() == 1 ? " episode" : " episodes"));
        adapter.submitList(episodes);
        adapter.setDownloadMode(downloadMode);
        binding.rvEpisodes.setLayoutManager(downloadMode ? new GridLayoutManager(requireContext(), 4) : new LinearLayoutManager(requireContext()));
        binding.layoutDownloadTools.setVisibility(downloadMode ? View.VISIBLE : View.GONE);
        binding.layoutDownloadBar.setVisibility(downloadMode ? View.VISIBLE : View.GONE);
        updateModeButtons();
    }

    /**
     * Releases references to views and adapters owned by this fragment view.
     */
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    /**
     * Cancels or detaches fragment-owned work that should not outlive the fragment instance.
     */
    @Override
    public void onDestroy() {
        episodeExecutor.shutdownNow();
        super.onDestroy();
    }
}
