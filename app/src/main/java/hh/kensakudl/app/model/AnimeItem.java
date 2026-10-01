package hh.kensakudl.app.model;

import com.google.gson.annotations.SerializedName;
import java.io.Serializable;
/**
 * Serializable anime metadata model shared by search, series, and bookmark screens.
 *
 * <p>Getters provide defensive defaults for fields that may be absent from upstream
 * metadata, while Gson uses the fields directly when persisting bookmarks.</p>
 */

public class AnimeItem implements Serializable {
    @SerializedName(value = "id", alternate = {"postId", "postid"})
    private String id;
    @SerializedName("anilist")
    private String anilist;
    @SerializedName("mal")
    private String mal;
    @SerializedName(value = "postName", alternate = {"postname"})
    private String postName;
    @SerializedName(value = "postYear", alternate = {"postyear"})
    private String postYear;
    @SerializedName(value = "aniTypes", alternate = {"anitypes"})
    private String aniTypes;
    @SerializedName(value = "postSeasonType", alternate = {"postseasontype"})
    private String postSeasonType;
    @SerializedName(value = "postStudios", alternate = {"poststudios"})
    private String postStudios;
    @SerializedName(value = "postAniGenres", alternate = {"postanigenres"})
    private String postAniGenres;
    @SerializedName(value = "aniCoverLarge", alternate = {"ani_cover_large"})
    private String aniCoverLarge;
    private String bannerUrl;
    private String backgroundUrl;
    private String logoUrl;
    private String status;
    private String source;
    private String countryOfOrigin;
    private String description;
    private int episodeCount;
    private int episodeDurationMinutes;
    private int popularity;
    private double averageScore;
    private boolean adult;

    public String getId() { return id != null ? id : ""; }
    public String getAnilist() { return anilist != null ? anilist : ""; }
    public String getMal() { return mal != null ? mal : ""; }
    public String getPostId() { return getId(); }
    public String getPostName() { return postName != null && !postName.isEmpty() ? postName : "Unknown"; }
    public String getPostYear() { return postYear != null && !postYear.isEmpty() ? postYear : "?"; }
    public String getAniTypes() { return aniTypes != null && !aniTypes.isEmpty() ? aniTypes : "Anime"; }
    public String getPostSeasonType() { return postSeasonType != null ? postSeasonType : ""; }
    public String getPostStudios() { return postStudios != null ? postStudios : ""; }
    public String getPostAniGenres() { return postAniGenres != null ? postAniGenres : ""; }
    public String getAniCoverLarge() { return aniCoverLarge != null ? aniCoverLarge : ""; }
    public String getBannerUrl() { return bannerUrl != null ? bannerUrl : ""; }
    public String getBackgroundUrl() { return backgroundUrl != null ? backgroundUrl : ""; }
    public String getLogoUrl() { return logoUrl != null ? logoUrl : ""; }
    public String getStatus() { return status != null ? status : ""; }
    public String getSource() { return source != null ? source : ""; }
    public String getCountryOfOrigin() { return countryOfOrigin != null ? countryOfOrigin : ""; }
    public String getDescription() { return description != null ? description : ""; }
    public int getEpisodeCount() { return episodeCount; }
    public int getEpisodeDurationMinutes() { return episodeDurationMinutes; }
    public int getPopularity() { return popularity; }
    public double getAverageScore() { return averageScore; }
    public boolean isAdult() { return adult; }

    public void setId(String id) { this.id = id; }
    public void setAnilist(String anilist) { this.anilist = anilist; }
    public void setMal(String mal) { this.mal = mal; }
    public void setPostName(String postName) { this.postName = postName; }
    public void setPostYear(String postYear) { this.postYear = postYear; }
    public void setAniTypes(String aniTypes) { this.aniTypes = aniTypes; }
    public void setPostSeasonType(String postSeasonType) { this.postSeasonType = postSeasonType; }
    public void setPostStudios(String postStudios) { this.postStudios = postStudios; }
    public void setPostAniGenres(String postAniGenres) { this.postAniGenres = postAniGenres; }
    public void setAniCoverLarge(String aniCoverLarge) { this.aniCoverLarge = aniCoverLarge; }
    public void setBannerUrl(String bannerUrl) { this.bannerUrl = bannerUrl; }
    public void setBackgroundUrl(String backgroundUrl) { this.backgroundUrl = backgroundUrl; }
    public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }
    public void setStatus(String status) { this.status = status; }
    public void setSource(String source) { this.source = source; }
    public void setCountryOfOrigin(String countryOfOrigin) { this.countryOfOrigin = countryOfOrigin; }
    public void setDescription(String description) { this.description = description; }
    public void setEpisodeCount(int episodeCount) { this.episodeCount = episodeCount; }
    public void setEpisodeDurationMinutes(int episodeDurationMinutes) { this.episodeDurationMinutes = episodeDurationMinutes; }
    public void setPopularity(int popularity) { this.popularity = popularity; }
    public void setAverageScore(double averageScore) { this.averageScore = averageScore; }
    public void setAdult(boolean adult) { this.adult = adult; }
}
