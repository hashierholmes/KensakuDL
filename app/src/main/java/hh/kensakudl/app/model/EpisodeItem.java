package hh.kensakudl.app.model;

import java.io.Serializable;

/**
 * Serializable episode metadata shared by the series UI and downloader. It also carries the selected subtitle or dub track and the transient selection state used for batch actions.
 */
public class EpisodeItem implements Serializable {
    private int episodeNumber;
    private int absoluteNumber;
    private String animeId;
    private String name;
    private String title;
    private String slug;
    private String link;
    private String synopsis;
    private String thumbnailUrl;
    private String airedOn;
    private int durationSeconds;
    private String kind;
    private String canonType;
    private boolean selected;
    private String track = "sub";

    public String getName() {
        return episodeNumber > 0 ? String.valueOf(episodeNumber) : (name != null ? name : "");
    }

    public String getTitle() {
        if (title != null && !title.trim().isEmpty()) return title.trim();
        if (name != null && !name.trim().isEmpty() && !name.trim().equals(String.valueOf(episodeNumber))) return name.trim();
        return "";
    }
    public String getSlug() { return slug != null ? slug : ""; }
    public String getLink() { return link != null ? link : ""; }
    public String getAnimeId() { return animeId != null ? animeId : ""; }
    public int getEpisodeNumber() { return episodeNumber; }
    public int getAbsoluteNumber() { return absoluteNumber; }
    public String getSynopsis() { return synopsis != null ? synopsis : ""; }
    public String getThumbnailUrl() { return thumbnailUrl != null ? thumbnailUrl : ""; }
    public String getAiredOn() { return airedOn != null ? airedOn : ""; }
    public int getDurationSeconds() { return durationSeconds; }
    public String getKind() { return kind != null ? kind : ""; }
    public String getCanonType() { return canonType != null ? canonType : ""; }
    public boolean isSelected() { return selected; }
    public String getTrack() { return track != null && !track.isEmpty() ? track : "sub"; }

    public void setEpisodeNumber(int episodeNumber) { this.episodeNumber = episodeNumber; }
    public void setAbsoluteNumber(int absoluteNumber) { this.absoluteNumber = absoluteNumber; }
    public void setAnimeId(String animeId) { this.animeId = animeId; }
    public void setName(String name) { this.name = name; }
    public void setTitle(String title) { this.title = title; }
    public void setSlug(String slug) { this.slug = slug; }
    public void setLink(String link) { this.link = link; }
    public void setSynopsis(String synopsis) { this.synopsis = synopsis; }
    public void setThumbnailUrl(String thumbnailUrl) { this.thumbnailUrl = thumbnailUrl; }
    public void setAiredOn(String airedOn) { this.airedOn = airedOn; }
    public void setDurationSeconds(int durationSeconds) { this.durationSeconds = durationSeconds; }
    public void setKind(String kind) { this.kind = kind; }
    public void setCanonType(String canonType) { this.canonType = canonType; }
    public void setSelected(boolean selected) { this.selected = selected; }
    public void setTrack(String track) { this.track = "dub".equalsIgnoreCase(track) ? "dub" : "sub"; }
}
