package hh.kensakudl.app.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import hh.kensakudl.app.R;
import hh.kensakudl.app.model.AnimeItem;
import java.util.ArrayList;
import java.util.List;

/**
 * Binds a horizontal home shelf to its anime items and reports selected titles to the home screen.
 */
public class HomeShelfAdapter extends RecyclerView.Adapter<HomeShelfAdapter.Holder> {
    public interface Listener { void onClick(AnimeItem item); }
    private final List<AnimeItem> items = new ArrayList<>(); private final Listener listener;
    public HomeShelfAdapter(Listener listener) { this.listener = listener; }
    /**
     * Replaces the shelf contents and refreshes the horizontal list.
     * 
     * @param data anime items to display; {@code null} clears the shelf
     */
    public void submit(List<AnimeItem> data) { items.clear(); if(data!=null) items.addAll(data); notifyDataSetChanged(); }
    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup p,int t) { return new Holder(LayoutInflater.from(p.getContext()).inflate(R.layout.item_home_shelf,p,false)); }
    @Override public void onBindViewHolder(@NonNull Holder h,int pos) { AnimeItem a=items.get(pos); h.title.setText(a.getPostName()); String meta=a.getPostYear(); double score=a.getAverageScore(); if(score>0){ if(score>10) score/=10.0; else if(score<=1.0) score*=10.0; meta += "  •  ★ " + String.format(java.util.Locale.US,"%.1f",score); } h.meta.setText(meta.trim()); Glide.with(h.cover).load(a.getAniCoverLarge()).placeholder(R.drawable.bg_pill).error(R.drawable.bg_pill).into(h.cover); h.itemView.setOnClickListener(v->listener.onClick(a)); }
    @Override public int getItemCount(){return items.size();}
    static class Holder extends RecyclerView.ViewHolder { ImageView cover; TextView title,meta; Holder(View v){super(v);cover=v.findViewById(R.id.ivCover);title=v.findViewById(R.id.tvTitle);meta=v.findViewById(R.id.tvMeta);} }
}
