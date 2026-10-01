package hh.kensakudl.app.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import hh.kensakudl.app.databinding.ItemEpisodeBinding;
import hh.kensakudl.app.model.EpisodeItem;
import java.util.ArrayList;
import java.util.List;
/**
 * Binds episode metadata and maintains multi-selection state for batch downloads.
 *
 * <p>Selection changes are reported through {@link OnSelectionChangedListener} so the
 * owning screen can update its action UI without coupling the adapter to a fragment.</p>
 */

public class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.ViewHolder> {
    public interface OnSelectionChangedListener {
        void onSelectionChanged(int count);
    }

    private final List<EpisodeItem> list = new ArrayList<>();
    private final OnSelectionChangedListener listener;

    public EpisodeAdapter(OnSelectionChangedListener listener) {
        this.listener = listener;
    }
/**
 * Replaces the episode list and clears the previous selection state.
 *
 * @param items new episode list; {@code null} is treated as an empty list
 */

    public void submitList(List<EpisodeItem> items) {
        list.clear();
        if (items != null) list.addAll(items);
        notifyDataSetChanged();
    }
/**
 * Selects or clears every episode currently displayed.
 *
 * @param select whether all displayed episodes should be selected
 */

    public void selectAll(boolean select) {
        for (EpisodeItem it : list) it.setSelected(select);
        notifyDataSetChanged();
        if (listener != null) listener.onSelectionChanged(getSelectedCount());
    }
/**
 * Selects the inclusive range of displayed episodes.
 *
 * @param start first adapter position
 * @param end last adapter position
 */

    public void selectRange(int start, int end) {
        int s = Math.max(0, start);
        int e = Math.min(list.size() - 1, end);
        for (int i = s; i <= e; i++) {
            list.get(i).setSelected(true);
        }
        notifyDataSetChanged();
        if (listener != null) listener.onSelectionChanged(getSelectedCount());
    }
/**
 * Returns a snapshot of the currently selected episodes.
 *
 * @return selected episode objects in adapter order
 */

    public List<EpisodeItem> getSelectedEpisodes() {
        List<EpisodeItem> selected = new ArrayList<>();
        for (EpisodeItem it : list) {
            if (it.isSelected()) selected.add(it);
        }
        return selected;
    }
/**
 * Returns the number of selected episodes.
 *
 * @return current selection count
 */

    public int getSelectedCount() {
        int c = 0;
        for (EpisodeItem it : list) {
            if (it.isSelected()) c++;
        }
        return c;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemEpisodeBinding binding = ItemEpisodeBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false
        );
        return new ViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        EpisodeItem item = list.get(position);
        holder.binding.tvEpisodeName.setText("Episode " + item.getName());
        holder.binding.cbEpisode.setChecked(item.isSelected());

        holder.itemView.setOnClickListener(v -> {
            item.setSelected(!item.isSelected());
            holder.binding.cbEpisode.setChecked(item.isSelected());
            if (listener != null) listener.onSelectionChanged(getSelectedCount());
        });
    }

    @Override
    public int getItemCount() {
        return list.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ItemEpisodeBinding binding;
        ViewHolder(ItemEpisodeBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
