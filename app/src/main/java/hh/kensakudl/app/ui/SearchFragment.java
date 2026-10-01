package hh.kensakudl.app.ui;

import android.os.Bundle;
import android.os.CountDownTimer;
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
import androidx.recyclerview.widget.GridLayoutManager;
import hh.kensakudl.app.MainActivity;
import hh.kensakudl.app.adapter.AnimeCardAdapter;
import hh.kensakudl.app.databinding.FragmentSearchBinding;
import hh.kensakudl.app.model.AnimeItem;
import hh.kensakudl.app.network.ApiClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
/**
 * Provides anime discovery, search, and random-title browsing.
 *
 * <p>Search requests run away from the main thread and the fragment retains its current
 * results in memory while navigating between the app's primary tabs.</p>
 */

public class SearchFragment extends Fragment {
    private FragmentSearchBinding binding;
    private AnimeCardAdapter adapter;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isRandomizing = false;
    private CountDownTimer cooldownTimer;

    
    private static final List<AnimeItem> cachedDiscoverAnime = new ArrayList<>();

    private static final String[] SEED_KEYWORDS = {
            "Solo", "Hero", "Titan", "Demon", "Piece", "Jujutsu", "Dragon", "Naruto",
            "Bleach", "Hunter", "Sword", "Fate", "Death", "Alchemy", "Dungeon", "Moon"
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentSearchBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        int span = getResources().getConfiguration().screenWidthDp >= 600 ? 4 : 2;
        binding.rvAnimeGrid.setLayoutManager(new GridLayoutManager(requireContext(), span));

        adapter = new AnimeCardAdapter(anime -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).openSeries(anime);
            }
        });
        binding.rvAnimeGrid.setAdapter(adapter);

        binding.btnSearch.setOnClickListener(v -> {
            String q = binding.etSearchQuery.getText().toString().trim();
            if (!q.isEmpty()) performSearch(q);
        });

        binding.btnRandomize.setOnClickListener(v -> handleRandomize());

        
        if (!cachedDiscoverAnime.isEmpty()) {
            adapter.submitList(new ArrayList<>(cachedDiscoverAnime));
        } else {
            loadDiscoverAnime();
        }
    }

    private void performSearch(String query) {
        binding.progressBar.setVisibility(View.VISIBLE);
        binding.tvSectionTitle.setText("Search Results for \"" + query + "\"");

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                List<AnimeItem> results = ApiClient.getInstance().searchAnime(query);
                mainHandler.post(() -> {
                    binding.progressBar.setVisibility(View.GONE);
                    adapter.submitList(results);
                    if (results.isEmpty()) {
                        Toast.makeText(requireContext(), "No anime found", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    binding.progressBar.setVisibility(View.GONE);
                    Log.e("SearchFragment", "Search request failed", e);
                    Toast.makeText(requireContext(), "Unable to search. Please check your internet connection and try again.", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void handleRandomize() {
        if (isRandomizing) return;
        isRandomizing = true;
        binding.btnRandomize.setEnabled(false);

        cooldownTimer = new CountDownTimer(5000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                if (isAdded()) {
                    binding.btnRandomize.setText("Randomize (" + (millisUntilFinished / 1000 + 1) + "s)");
                }
            }

            @Override
            public void onFinish() {
                if (isAdded()) {
                    isRandomizing = false;
                    binding.btnRandomize.setEnabled(true);
                    binding.btnRandomize.setText("Randomize");
                }
            }
        }.start();

        loadDiscoverAnime();
    }

    private void loadDiscoverAnime() {
        binding.progressBar.setVisibility(View.VISIBLE);
        binding.tvSectionTitle.setText("Discover Anime");

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                String seed1 = SEED_KEYWORDS[new Random().nextInt(SEED_KEYWORDS.length)];
                String seed2 = SEED_KEYWORDS[new Random().nextInt(SEED_KEYWORDS.length)];

                List<AnimeItem> list1 = ApiClient.getInstance().searchAnime(seed1);
                List<AnimeItem> list2 = ApiClient.getInstance().searchAnime(seed2);

                List<AnimeItem> combined = new ArrayList<>(list1);
                for (AnimeItem it : list2) {
                    if (!combined.contains(it)) combined.add(it);
                }

                Collections.shuffle(combined);
                List<AnimeItem> fifteen = combined.subList(0, Math.min(15, combined.size()));

                mainHandler.post(() -> {
                    binding.progressBar.setVisibility(View.GONE);
                    cachedDiscoverAnime.clear();
                    cachedDiscoverAnime.addAll(fifteen);
                    adapter.submitList(fifteen);
                });
            } catch (Exception e) {
                Log.e("SearchFragment", "Discover request failed", e);
                mainHandler.post(() -> binding.progressBar.setVisibility(View.GONE));
            }
        });
    }

    @Override
    public void onDestroyView() {
        if (cooldownTimer != null) cooldownTimer.cancel();
        super.onDestroyView();
        binding = null;
    }
}