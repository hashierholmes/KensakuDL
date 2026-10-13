package hh.kensakudl.app.adapter;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import hh.kensakudl.app.model.EpisodeItem;
import java.util.ArrayList;
import java.util.List;

/**
 * Presents an anime's episodes and tracks selection state when the list is used for batch downloads.
 */
public class EpisodeAdapter extends RecyclerView.Adapter<EpisodeAdapter.ViewHolder> {
    public interface OnSelectionChangedListener { void onSelectionChanged(int count); }
    public interface OnEpisodeClickListener { void onEpisodeClick(EpisodeItem item); }
    private final List<EpisodeItem> list = new ArrayList<>();
    private final OnSelectionChangedListener listener;
    private final OnEpisodeClickListener episodeClickListener;
    private boolean downloadMode = true;

    public EpisodeAdapter(OnSelectionChangedListener listener) { this(listener, null); }
    public EpisodeAdapter(OnSelectionChangedListener listener, OnEpisodeClickListener clickListener) {
        this.listener = listener; this.episodeClickListener = clickListener;
    }
    /**
     * Switches the row presentation between browsing and multi-select download mode.
     * 
     * @param mode {@code true} to enable download selection controls
     */
    public void setDownloadMode(boolean mode) { downloadMode = mode; notifyDataSetChanged(); }
    /**
     * Indicates whether episode rows currently support download selection.
     * 
     * @return {@code true} when download mode is enabled
     */
    public boolean isDownloadMode() { return downloadMode; }
    /**
     * Replaces the episode rows while preserving the adapter as the single source of list contents.
     * 
     * @param items episodes to display; {@code null} clears the list
     */
    public void submitList(List<EpisodeItem> items) { list.clear(); if (items != null) list.addAll(items); notifyDataSetChanged(); }
    /**
     * Sets the selection state of every displayed episode and notifies the selection listener.
     * 
     * @param select {@code true} to select every episode, or {@code false} to clear selection
     */
    public void selectAll(boolean select) { for (EpisodeItem it : list) it.setSelected(select); notifyDataSetChanged(); if (listener != null) listener.onSelectionChanged(getSelectedCount()); }
    /**
     * Returns the selected episodes in their current display order.
     * 
     * @return a new list containing selected episodes
     */
    public List<EpisodeItem> getSelectedEpisodes() { List<EpisodeItem> out = new ArrayList<>(); for (EpisodeItem it : list) if (it.isSelected()) out.add(it); return out; }
    /**
     * Counts the episodes currently marked for selection.
     * 
     * @return number of selected episodes
     */
    public int getSelectedCount() { int c=0; for (EpisodeItem it:list) if(it.isSelected()) c++; return c; }
    @NonNull @Override public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View root = LayoutInflater.from(parent.getContext()).inflate(hh.kensakudl.app.R.layout.item_episode_modern, parent, false);
        return new ViewHolder(root);
    }
    @Override public void onBindViewHolder(@NonNull ViewHolder h, int position) {
        EpisodeItem item = list.get(position); int number = item.getEpisodeNumber() > 0 ? item.getEpisodeNumber() : position + 1;
        h.title.setText(downloadMode ? String.valueOf(number) : ("EP " + number));
        h.title.setTextColor(Color.WHITE); h.title.setTypeface(null, Typeface.BOLD);
        String actualTitle = item.getTitle();
        h.subtitle.setVisibility(downloadMode || actualTitle.isEmpty() ? View.GONE : View.VISIBLE);
        if (!downloadMode && !actualTitle.isEmpty()) h.subtitle.setText(actualTitle);
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(downloadMode ? 22f : 18f);
        bg.setColor(item.isSelected() ? Color.rgb(139, 43, 63) : Color.rgb(29, 32, 46));
        bg.setStroke(1, item.isSelected() ? Color.rgb(164, 56, 79) : Color.rgb(55, 59, 78)); h.itemView.setBackground(bg);
        h.title.setGravity(android.view.Gravity.CENTER);
        h.itemView.setMinimumHeight(downloadMode ? 58 : 72);
        h.title.setTextSize(downloadMode ? 18 : 11);
        h.title.setMaxLines(1);
        h.itemView.setOnClickListener(v -> {
            if (downloadMode) { item.setSelected(!item.isSelected()); notifyItemChanged(h.getBindingAdapterPosition()); if (listener != null) listener.onSelectionChanged(getSelectedCount()); }
            else if (episodeClickListener != null) episodeClickListener.onEpisodeClick(item);
        });
    }
    @Override public int getItemCount() { return list.size(); }
    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView title, subtitle;
        ViewHolder(View root) { super(root); title = root.findViewById(hh.kensakudl.app.R.id.tvTileNumber); subtitle = root.findViewById(hh.kensakudl.app.R.id.tvEpisodeTitle); }
    }
}
