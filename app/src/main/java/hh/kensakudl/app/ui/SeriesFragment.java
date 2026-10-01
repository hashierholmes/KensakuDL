package hh.kensakudl.app.ui;

import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
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
import java.util.List;
import java.util.concurrent.Executors;
/**
 * Displays metadata and available episodes for a selected anime series.
 */

public class SeriesFragment extends Fragment {
    private static final String ARG_ANIME = "arg_anime";
    private FragmentSeriesBinding binding;
    private AnimeItem anime;
    private EpisodeAdapter adapter;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
/**
 * Creates a series fragment carrying the selected anime as its argument.
 *
 * @param anime anime metadata to display
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
        binding.tvYear.setText(anime.getPostYear());
        binding.tvType.setText(anime.getAniTypes());
        binding.tvSeason.setText(anime.getPostSeasonType());
        binding.tvGenres.setText(anime.getPostAniGenres());

        
        Glide.with(this)
                .load(anime.getAniCoverLarge())
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .placeholder(R.drawable.bg_pill)
                .error(R.drawable.bg_pill)
                .into(binding.ivSeriesCover);

        binding.rvEpisodes.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new EpisodeAdapter(count -> {
            binding.tvSelectedCount.setText(count + " episodes selected");
            binding.btnDownloadSelected.setEnabled(count > 0);
        });
        binding.rvEpisodes.setAdapter(adapter);

        binding.btnSelectAll.setOnClickListener(v -> adapter.selectAll(true));
        binding.btnClear.setOnClickListener(v -> adapter.selectAll(false));

        binding.btnDownloadSelected.setOnClickListener(v -> {
            List<EpisodeItem> selected = adapter.getSelectedEpisodes();

            if (selected.isEmpty()) {
                return;
            }

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

        loadEpisodes();
    }

    private void loadEpisodes() {
        binding.pbEpisodes.setVisibility(View.VISIBLE);
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                List<EpisodeItem> episodes = ApiClient.getInstance().getEpisodes(anime.getId());
                mainHandler.post(() -> {
                    binding.pbEpisodes.setVisibility(View.GONE);
                    adapter.submitList(episodes);
                    if (episodes.isEmpty()) {
                        Toast.makeText(requireContext(), "No episodes found", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    binding.pbEpisodes.setVisibility(View.GONE);
                    Log.e("SeriesFragment", "Episode request failed", e);
                    Toast.makeText(requireContext(), "Unable to load episodes. Please check your internet connection and try again.", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
