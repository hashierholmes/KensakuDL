package hh.kensakudl.app.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import hh.kensakudl.app.R;
import hh.kensakudl.app.databinding.ItemAnimeCardBinding;
import hh.kensakudl.app.model.AnimeItem;
import java.util.ArrayList;
import java.util.List;

/**
 * Binds anime metadata to the compact poster cards used by catalogue lists and forwards card selections to the owning screen.
 */
public class AnimeCardAdapter extends RecyclerView.Adapter<AnimeCardAdapter.ViewHolder> {
    public interface OnAnimeClickListener {
        void onAnimeClick(AnimeItem anime);
    }

    private final List<AnimeItem> list = new ArrayList<>();
    private final OnAnimeClickListener listener;

    public AnimeCardAdapter(OnAnimeClickListener listener) {
        this.listener = listener;
    }

    /**
     * Replaces the adapter's current catalogue items and refreshes the visible cards.
     * 
     * @param items new items; {@code null} clears the list
     */
    public void submitList(List<AnimeItem> items) {
        list.clear();
        if (items != null) list.addAll(items);
        notifyDataSetChanged();
    }

    /**
     * Inflates the card layout used for one anime entry.
     * 
     * @param parent RecyclerView parent that will host the card
     * @param viewType adapter view type
     * @return holder for the inflated card
     */
    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemAnimeCardBinding binding = ItemAnimeCardBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false
        );
        return new ViewHolder(binding);
    }

    /**
     * Binds the anime at {@code position} and wires its click action.
     * 
     * @param holder card holder to update
     * @param position position of the anime in the current list
     */
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AnimeItem anime = list.get(position);
        holder.binding.tvAnimeTitle.setText(anime.getPostName());
        holder.binding.tvAnimeMeta.setText(anime.getPostYear() + " • " + anime.getAniTypes());

        // DIRECT REMOTE URL ONLY: Never downloaded or cached to local disk
        Glide.with(holder.itemView.getContext())
                .load(anime.getAniCoverLarge())
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .placeholder(R.drawable.bg_pill)
                .error(R.drawable.bg_pill)
                .into(holder.binding.ivCover);

        holder.itemView.setOnClickListener(v -> listener.onAnimeClick(anime));
    }

    /**
     * Returns the number of anime entries currently held by the adapter.
     * 
     * @return current item count
     */
    @Override
    public int getItemCount() {
        return list.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ItemAnimeCardBinding binding;
        ViewHolder(ItemAnimeCardBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
