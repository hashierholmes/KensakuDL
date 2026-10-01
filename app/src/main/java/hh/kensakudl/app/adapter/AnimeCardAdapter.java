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
 * Binds searchable anime metadata to the series card list.
 *
 * <p>Cover images are loaded from their remote URLs without enabling Glide's disk cache;
 * the adapter is intentionally limited to presentation and click handling.</p>
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
 * Replaces the cards displayed by the adapter.
 *
 * @param items new anime list; {@code null} is treated as an empty list
 */

    public void submitList(List<AnimeItem> items) {
        list.clear();
        if (items != null) list.addAll(items);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemAnimeCardBinding binding = ItemAnimeCardBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false
        );
        return new ViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AnimeItem anime = list.get(position);
        holder.binding.tvAnimeTitle.setText(anime.getPostName());
        holder.binding.tvAnimeMeta.setText(anime.getPostYear() + " • " + anime.getAniTypes());

        
        Glide.with(holder.itemView.getContext())
                .load(anime.getAniCoverLarge())
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .placeholder(R.drawable.bg_pill)
                .error(R.drawable.bg_pill)
                .into(holder.binding.ivCover);

        holder.itemView.setOnClickListener(v -> listener.onAnimeClick(anime));
    }

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
