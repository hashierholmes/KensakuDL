package hh.kensakudl.app.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import hh.kensakudl.app.MainActivity;
import hh.kensakudl.app.adapter.AnimeCardAdapter;
import hh.kensakudl.app.databinding.FragmentBookmarksBinding;
import hh.kensakudl.app.util.BookmarkManager;
/**
 * Displays the user's locally persisted anime bookmarks.
 */

public class BookmarksFragment extends Fragment {
    private FragmentBookmarksBinding binding;
    private AnimeCardAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentBookmarksBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        int span = getResources().getConfiguration().screenWidthDp >= 600 ? 4 : 2;
        binding.rvBookmarks.setLayoutManager(new GridLayoutManager(requireContext(), span));

        adapter = new AnimeCardAdapter(anime -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).openSeries(anime);
            }
        });
        binding.rvBookmarks.setAdapter(adapter);

        adapter.submitList(BookmarkManager.getInstance(requireContext()).getBookmarks());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
