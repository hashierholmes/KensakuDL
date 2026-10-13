package hh.kensakudl.app.ui;

import android.os.Bundle;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import hh.kensakudl.app.MainActivity;
import hh.kensakudl.app.adapter.AnimeCardAdapter;
import hh.kensakudl.app.adapter.HomeShelfAdapter;
import hh.kensakudl.app.databinding.FragmentSearchBinding;
import hh.kensakudl.app.model.AnimeItem;
import hh.kensakudl.app.network.ApiClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Implements the home catalogue experience, including search and the curated anime shelves.
 */
public class SearchFragment extends Fragment {
    private FragmentSearchBinding binding;
    private AnimeCardAdapter searchAdapter;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final List<HomeShelfAdapter> shelfAdapters = new ArrayList<>();
    private static List<AnimeItem> popularCache, ratedCache, airingCache, upcomingCache;
    private boolean showingSearch = false;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentSearchBinding.inflate(inflater, container, false); return binding.getRoot();
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        binding.rvAnimeGrid.setLayoutManager(new GridLayoutManager(requireContext(), getResources().getConfiguration().screenWidthDp >= 600 ? 4 : 2));
        searchAdapter = new AnimeCardAdapter(this::openAnime); binding.rvAnimeGrid.setAdapter(searchAdapter);
        setupShelf(binding.rvPopular); setupShelf(binding.rvTopRated); setupShelf(binding.rvTopAiring); setupShelf(binding.rvUpcoming);
        binding.btnSearch.setOnClickListener(v -> { String q=binding.etSearchQuery.getText().toString().trim(); if(!q.isEmpty()) performSearch(q); });
        binding.etSearchQuery.setOnEditorActionListener((v,action,event)->{String q=v.getText().toString().trim();if(!q.isEmpty())performSearch(q);return true;});
        binding.btnRetryHome.setOnClickListener(v -> loadHome());
        if (popularCache != null && ratedCache != null && airingCache != null && upcomingCache != null) {
            showShelfData(popularCache,ratedCache,airingCache,upcomingCache);
        } else loadHome();
    }

    private void setupShelf(RecyclerView rv) {
        rv.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        HomeShelfAdapter a = new HomeShelfAdapter(this::openAnime); rv.setAdapter(a); shelfAdapters.add(a);
    }
    private void openAnime(AnimeItem anime) { if(getActivity() instanceof MainActivity)((MainActivity)getActivity()).openSeries(anime); }

    private void loadHome() {
        if(binding==null)return;
        showingSearch = false;
        binding.rvAnimeGrid.setVisibility(View.GONE);
        binding.layoutHomeOffline.setVisibility(View.GONE);
        if (!hasNetworkConnection()) {
            binding.layoutSkeletons.setVisibility(View.GONE);
            binding.layoutHomeShelves.setVisibility(View.GONE);
            binding.layoutHomeOffline.setVisibility(View.VISIBLE);
            binding.tvSectionTitle.setText("Discover anime");
            return;
        }
        binding.layoutSkeletons.setVisibility(View.VISIBLE); binding.layoutHomeShelves.setVisibility(View.INVISIBLE);
        AlphaAnimation pulse = new AlphaAnimation(0.55f,1f); pulse.setDuration(700); pulse.setRepeatMode(AlphaAnimation.REVERSE); pulse.setRepeatCount(AlphaAnimation.INFINITE); binding.layoutSkeletons.startAnimation(pulse);
        executor.execute(() -> {
            List<AnimeItem> p=safe(ApiClient.getInstance()::getPopularAnime), r=safe(ApiClient.getInstance()::getTopRatedAnime), a=safe(ApiClient.getInstance()::getTopAiringAnime), u=safe(ApiClient.getInstance()::getUpcomingAnime);
            main.post(() -> { if(binding==null)return; binding.layoutSkeletons.clearAnimation(); binding.layoutSkeletons.setVisibility(View.GONE); binding.layoutHomeShelves.setVisibility(View.VISIBLE); if(p.isEmpty()&&r.isEmpty()&&a.isEmpty()&&u.isEmpty()){binding.layoutHomeShelves.setVisibility(View.GONE); binding.layoutHomeOffline.setVisibility(View.VISIBLE); binding.tvOfflineTitle.setText("Couldn't load anime"); binding.tvOfflineMessage.setText("Please check your internet connection, then try again. Your downloaded episodes are still available in Downloads."); return;} binding.layoutHomeOffline.setVisibility(View.GONE); popularCache=p; ratedCache=r; airingCache=a; upcomingCache=u; showShelfData(p,r,a,u); });
        });
    }
    private boolean hasNetworkConnection() {
        try {
            ConnectivityManager cm = (ConnectivityManager) requireContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            Network active = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities caps = active == null || cm == null ? null : cm.getNetworkCapabilities(active);
            return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        } catch (Exception e) { return true; }
    }
    private interface Fetch { List<AnimeItem> run() throws Exception; }
    private List<AnimeItem> safe(Fetch f) { try { return f.run(); } catch(Exception e){Log.e("HomeFeed","Feed request failed",e);return new ArrayList<>();} }
    private void showShelfData(List<AnimeItem> p,List<AnimeItem> r,List<AnimeItem> a,List<AnimeItem> u) { if(shelfAdapters.size()<4)return; shelfAdapters.get(0).submit(p);shelfAdapters.get(1).submit(r);shelfAdapters.get(2).submit(a);shelfAdapters.get(3).submit(u); }

    private void performSearch(String query) {
        if(binding==null)return; showingSearch=true; binding.layoutHomeOffline.setVisibility(View.GONE); binding.layoutHomeShelves.setVisibility(View.GONE); binding.layoutSkeletons.setVisibility(View.VISIBLE); binding.tvSectionTitle.setText("Results for “"+query+"”");
        if (!hasNetworkConnection()) { binding.layoutSkeletons.setVisibility(View.GONE); binding.layoutHomeOffline.setVisibility(View.VISIBLE); binding.tvOfflineTitle.setText("You're offline"); binding.tvOfflineMessage.setText("Connect to the internet to search anime. You can still play your downloaded episodes."); return; }
        executor.execute(() -> { List<AnimeItem> result=safe(() -> ApiClient.getInstance().searchAnime(query)); main.post(() -> { if(binding==null)return; binding.layoutSkeletons.setVisibility(View.GONE); binding.rvAnimeGrid.setVisibility(View.VISIBLE); searchAdapter.submitList(result); if(result.isEmpty())Toast.makeText(requireContext(),"No anime found",Toast.LENGTH_SHORT).show(); }); });
    }
    @Override public void onDestroyView() { if(binding!=null)binding.layoutSkeletons.clearAnimation(); shelfAdapters.clear(); super.onDestroyView(); binding=null; }
    @Override public void onDestroy() { super.onDestroy(); /* shared executor intentionally remains for fragment recreation */ }
}
